// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashingOutputStream;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.Retrier.RetryException;
import com.google.devtools.build.lib.remote.TreeNodeRepository.TreeNode;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.DigestUtil.ActionKey;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc.ActionCacheBlockingStub;
import com.google.devtools.remoteexecution.v1test.ActionResult;
import com.google.devtools.remoteexecution.v1test.Command;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.Directory;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsRequest;
import com.google.devtools.remoteexecution.v1test.FindMissingBlobsResponse;
import com.google.devtools.remoteexecution.v1test.GetActionResultRequest;
import com.google.devtools.remoteexecution.v1test.UpdateActionResultRequest;
import io.grpc.CallCredentials;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server. */
@ThreadSafe
public class GrpcRemoteCache extends AbstractRemoteActionCache {
  private final CallCredentials credentials;
  private final ReferenceCountedChannel channel;
  private final RemoteRetrier retrier;
  private final ByteStreamUploader uploader;

  private AtomicBoolean closed = new AtomicBoolean();

  @VisibleForTesting
  public GrpcRemoteCache(
      ReferenceCountedChannel channel,
      CallCredentials credentials,
      RemoteOptions options,
      RemoteRetrier retrier,
      DigestUtil digestUtil,
      ByteStreamUploader uploader) {
    super(options, digestUtil, retrier);
    this.credentials = credentials;
    this.channel = channel;
    this.retrier = retrier;
    this.uploader = uploader;
  }

  private ContentAddressableStorageBlockingStub casBlockingStub() {
    return ContentAddressableStorageGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ByteStreamStub bsAsyncStub() {
    return ByteStreamGrpc.newStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ActionCacheBlockingStub acBlockingStub() {
    return ActionCacheGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    uploader.release();
    channel.release();
  }

  public static boolean isRemoteCacheOptions(RemoteOptions options) {
    return options.remoteCache != null;
  }

  private ImmutableSet<Digest> getMissingDigests(Iterable<Digest> digests)
      throws IOException, InterruptedException {
    FindMissingBlobsRequest.Builder request =
        FindMissingBlobsRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .addAllBlobDigests(digests);
    if (request.getBlobDigestsCount() == 0) {
      return ImmutableSet.of();
    }
    FindMissingBlobsResponse response =
        retrier.execute(() -> casBlockingStub().findMissingBlobs(request.build()));
    return ImmutableSet.copyOf(response.getMissingBlobDigestsList());
  }

  /**
   * Upload enough of the tree metadata and data into remote cache so that the entire tree can be
   * reassembled remotely using the root digest.
   */
  @Override
  public void ensureInputsPresent(
      TreeNodeRepository repository, Path execRoot, TreeNode root, Command command)
      throws IOException, InterruptedException {
    repository.computeMerkleDigests(root);
    Digest commandDigest = digestUtil.compute(command);
    // TODO(olaola): avoid querying all the digests, only ask for novel subtrees.
    ImmutableSet<Digest> missingDigests =
        getMissingDigests(
            Iterables.concat(repository.getAllDigests(root), ImmutableList.of(commandDigest)));

    List<Chunker> toUpload = new ArrayList<>();
    // Only upload data that was missing from the cache.
    Map<Digest, ActionInput> missingActionInputs = new HashMap<>();
    Map<Digest, Directory> missingTreeNodes = new HashMap<>();
    HashSet<Digest> missingTreeDigests = new HashSet<>(missingDigests);
    missingTreeDigests.remove(commandDigest);
    repository.getDataFromDigests(missingTreeDigests, missingActionInputs, missingTreeNodes);

    if (missingDigests.contains(commandDigest)) {
      toUpload.add(
          Chunker.builder(digestUtil).setInput(commandDigest, command.toByteArray()).build());
    }
    if (!missingTreeNodes.isEmpty()) {
      for (Map.Entry<Digest, Directory> entry : missingTreeNodes.entrySet()) {
        Digest digest = entry.getKey();
        Directory d = entry.getValue();
        toUpload.add(Chunker.builder(digestUtil).setInput(digest, d.toByteArray()).build());
      }
    }
    if (!missingActionInputs.isEmpty()) {
      for (Map.Entry<Digest, ActionInput> entry : missingActionInputs.entrySet()) {
        Digest digest = entry.getKey();
        ActionInput actionInput = entry.getValue();
        toUpload.add(Chunker.builder(digestUtil).setInput(digest, actionInput, execRoot).build());
      }
    }
    uploader.uploadBlobs(toUpload, true);
  }

  @Override
  protected ListenableFuture<Void> downloadBlob(Digest digest, OutputStream out) {
    String resourceName = "";
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName += options.remoteInstanceName + "/";
    }
    resourceName += "blobs/" + digestUtil.toString(digest);

    HashingOutputStream hashOut = digestUtil.newHashingOutputStream(out);
    SettableFuture<Void> outerF = SettableFuture.create();
    bsAsyncStub()
        .read(
            ReadRequest.newBuilder().setResourceName(resourceName).build(),
            new StreamObserver<ReadResponse>() {
              @Override
              public void onNext(ReadResponse readResponse) {
                try {
                  readResponse.getData().writeTo(hashOut);
                } catch (IOException e) {
                  outerF.setException(e);
                  // Cancel the call.
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void onError(Throwable t) {
                if (t instanceof StatusRuntimeException
                    && ((StatusRuntimeException) t).getStatus().getCode()
                        == Status.NOT_FOUND.getCode()) {
                  outerF.setException(new CacheNotFoundException(digest, digestUtil));
                } else {
                  outerF.setException(t);
                }
              }

              @Override
              public void onCompleted() {
                String expectedHash = digest.getHash();
                String actualHash = DigestUtil.hashCodeToString(hashOut.hash());
                if (!expectedHash.equals(actualHash)) {
                  String msg =
                      String.format(
                          "Expected hash '%s' does not match received hash '%s'.",
                          expectedHash, actualHash);
                  outerF.setException(new IOException(msg));
                } else {
                  try {
                    out.flush();
                    outerF.set(null);
                  } catch (IOException e) {
                    outerF.setException(e);
                  }
                }
              }
            });
    return outerF;
  }

  @Override
  public void upload(
      ActionKey actionKey,
      Path execRoot,
      Collection<Path> files,
      FileOutErr outErr,
      boolean uploadAction)
      throws ExecException, IOException, InterruptedException {
    ActionResult.Builder result = ActionResult.newBuilder();
    upload(execRoot, files, outErr, result);
    if (!uploadAction) {
      return;
    }
    try {
      retrier.execute(
          () ->
              acBlockingStub()
                  .updateActionResult(
                      UpdateActionResultRequest.newBuilder()
                          .setInstanceName(options.remoteInstanceName)
                          .setActionDigest(actionKey.getDigest())
                          .setActionResult(result)
                          .build()));
    } catch (RetryException e) {
      if (RemoteRetrierUtils.causedByStatus(e, Status.Code.UNIMPLEMENTED)) {
        // Silently return without upload.
        return;
      }
      throw e;
    }
  }

  void upload(Path execRoot, Collection<Path> files, FileOutErr outErr, ActionResult.Builder result)
      throws ExecException, IOException, InterruptedException {
    UploadManifest manifest =
        new UploadManifest(digestUtil, result, execRoot, options.allowSymlinkUpload);
    manifest.addFiles(files);

    List<Chunker> filesToUpload = new ArrayList<>();

    Map<Digest, Path> digestToFile = manifest.getDigestToFile();
    Map<Digest, Chunker> digestToChunkers = manifest.getDigestToChunkers();
    Collection<Digest> digests = new ArrayList<>();
    digests.addAll(digestToFile.keySet());
    digests.addAll(digestToChunkers.keySet());

    ImmutableSet<Digest> digestsToUpload = getMissingDigests(digests);
    for (Digest digest : digestsToUpload) {
      Chunker chunker;
      Path file = digestToFile.get(digest);
      if (file != null) {
        chunker = Chunker.builder(digestUtil).setInput(digest, file).build();
      } else {
        chunker = digestToChunkers.get(digest);
        if (chunker == null) {
          String message = "FindMissingBlobs call returned an unknown digest: " + digest;
          throw new IOException(message);
        }
      }
      filesToUpload.add(chunker);
    }

    if (!filesToUpload.isEmpty()) {
      uploader.uploadBlobs(filesToUpload, /*forceUpload=*/true);
    }

    // TODO(olaola): inline small stdout/stderr here.
    if (outErr.getErrorPath().exists()) {
      Digest stderr = uploadFileContents(outErr.getErrorPath());
      result.setStderrDigest(stderr);
    }
    if (outErr.getOutputPath().exists()) {
      Digest stdout = uploadFileContents(outErr.getOutputPath());
      result.setStdoutDigest(stdout);
    }
  }

  /**
   * Put the file contents cache if it is not already in it. No-op if the file is already stored in
   * cache. The given path must be a full absolute path.
   *
   * @return The key for fetching the file contents blob from cache.
   */
  private Digest uploadFileContents(Path file) throws IOException, InterruptedException {
    Digest digest = digestUtil.compute(file);
    ImmutableSet<Digest> missing = getMissingDigests(ImmutableList.of(digest));
    if (!missing.isEmpty()) {
      uploader.uploadBlob(Chunker.builder(digestUtil).setInput(digest, file).build(), true);
    }
    return digest;
  }

  Digest uploadBlob(byte[] blob) throws IOException, InterruptedException {
    Digest digest = digestUtil.compute(blob);
    ImmutableSet<Digest> missing = getMissingDigests(ImmutableList.of(digest));
    if (!missing.isEmpty()) {
      uploader.uploadBlob(Chunker.builder(digestUtil).setInput(digest, blob).build(), true);
    }
    return digest;
  }

  // Execution Cache API

  @Override
  public ActionResult getCachedActionResult(ActionKey actionKey)
      throws IOException, InterruptedException {
    try {
      return retrier.execute(
          () ->
              acBlockingStub()
                  .getActionResult(
                      GetActionResultRequest.newBuilder()
                          .setInstanceName(options.remoteInstanceName)
                          .setActionDigest(actionKey.getDigest())
                          .build()));
    } catch (RetryException e) {
      if (RemoteRetrierUtils.causedByStatus(e, Status.Code.NOT_FOUND)) {
        // Return null to indicate that it was a cache miss.
        return null;
      }
      throw e;
    }
  }
}
