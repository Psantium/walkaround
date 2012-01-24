/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.walkaround.wave.server.googleimport;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.walkaround.proto.ConvMetadata;
import com.google.walkaround.proto.FetchAttachmentsAndImportWaveletTask;
import com.google.walkaround.proto.FetchAttachmentsAndImportWaveletTask.RemoteAttachmentInfo;
import com.google.walkaround.proto.GoogleImport.GoogleDocument;
import com.google.walkaround.proto.GoogleImport.GoogleDocumentContent;
import com.google.walkaround.proto.GoogleImport.GoogleDocumentContent.Component;
import com.google.walkaround.proto.GoogleImport.GoogleDocumentContent.ElementStart;
import com.google.walkaround.proto.GoogleImport.GoogleDocumentContent.KeyValuePair;
import com.google.walkaround.proto.GoogleImport.GoogleWavelet;
import com.google.walkaround.proto.ImportMetadata;
import com.google.walkaround.proto.ImportTaskPayload;
import com.google.walkaround.proto.ImportWaveletTask;
import com.google.walkaround.proto.ImportWaveletTask.ImportSharingMode;
import com.google.walkaround.proto.ImportWaveletTask.ImportedAttachment;
import com.google.walkaround.proto.gson.ConvMetadataGsonImpl;
import com.google.walkaround.proto.gson.FetchAttachmentsAndImportWaveletTaskGsonImpl;
import com.google.walkaround.proto.gson.FetchAttachmentsAndImportWaveletTaskGsonImpl.RemoteAttachmentInfoGsonImpl;
import com.google.walkaround.proto.gson.ImportMetadataGsonImpl;
import com.google.walkaround.proto.gson.ImportTaskPayloadGsonImpl;
import com.google.walkaround.slob.server.MutationLog;
import com.google.walkaround.slob.server.SlobFacilities;
import com.google.walkaround.slob.shared.ChangeData;
import com.google.walkaround.slob.shared.ChangeRejected;
import com.google.walkaround.slob.shared.ClientId;
import com.google.walkaround.slob.shared.SlobId;
import com.google.walkaround.slob.shared.StateAndVersion;
import com.google.walkaround.util.server.RetryHelper;
import com.google.walkaround.util.server.RetryHelper.PermanentFailure;
import com.google.walkaround.util.server.RetryHelper.RetryableFailure;
import com.google.walkaround.util.server.appengine.CheckedDatastore;
import com.google.walkaround.util.server.appengine.CheckedDatastore.CheckedTransaction;
import com.google.walkaround.util.shared.Assert;
import com.google.walkaround.wave.server.WaveletCreator;
import com.google.walkaround.wave.server.attachment.AttachmentId;
import com.google.walkaround.wave.server.auth.StableUserId;
import com.google.walkaround.wave.server.conv.ConvMetadataStore;
import com.google.walkaround.wave.server.conv.ConvStore;
import com.google.walkaround.wave.server.googleimport.conversion.AttachmentIdConverter;
import com.google.walkaround.wave.server.googleimport.conversion.HistorySynthesizer;
import com.google.walkaround.wave.server.googleimport.conversion.PrivateReplyAnchorLegacyIdConverter;
import com.google.walkaround.wave.server.googleimport.conversion.StripWColonFilter;
import com.google.walkaround.wave.server.googleimport.conversion.WaveletHistoryConverter;
import com.google.walkaround.wave.server.gxp.SourceInstance;
import com.google.walkaround.wave.server.model.ServerMessageSerializer;
import com.google.walkaround.wave.server.model.WaveObjectStoreModel.ReadableWaveletObject;
import com.google.walkaround.wave.shared.WaveSerializer;

import org.waveprotocol.wave.migration.helpers.FixLinkAnnotationsFilter;
import org.waveprotocol.wave.model.document.operation.Nindo;
import org.waveprotocol.wave.model.id.IdConstants;
import org.waveprotocol.wave.model.id.IdUtil;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.operation.wave.WaveletOperation;
import org.waveprotocol.wave.model.util.Pair;
import org.waveprotocol.wave.model.wave.ParticipantId;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Processes {@link ImportWaveletTask}s.
 *
 * @author ohler@google.com (Christian Ohler)
 */
public class ImportWaveletProcessor {

  @SuppressWarnings("unused")
  private static final Logger log = Logger.getLogger(ImportWaveletProcessor.class.getName());

  private static final WaveSerializer SERIALIZER =
      new WaveSerializer(new ServerMessageSerializer());

  private final RobotApi.Factory robotApiFactory;
  private final SourceInstance.Factory sourceInstanceFactory;
  private final WaveletCreator waveletCreator;
  private final ParticipantId importingUser;
  private final StableUserId userId;
  private final PerUserTable perUserTable;
  private final SharedImportTable sharedImportTable;
  private final CheckedDatastore datastore;
  private final SlobFacilities convSlobFacilities;
  private final ConvMetadataStore convMetadataStore;

  @Inject
  public ImportWaveletProcessor(RobotApi.Factory robotApiFactory,
      SourceInstance.Factory sourceInstanceFactory,
      WaveletCreator waveletCreator,
      ParticipantId importingUser,
      StableUserId userId,
      PerUserTable perUserTable,
      SharedImportTable sharedImportTable,
      CheckedDatastore datastore,
      @ConvStore SlobFacilities slobFacilities,
      ConvMetadataStore convMetadataStore) {
    this.robotApiFactory = robotApiFactory;
    this.sourceInstanceFactory = sourceInstanceFactory;
    this.waveletCreator = waveletCreator;
    this.importingUser = importingUser;
    this.userId = userId;
    this.perUserTable = perUserTable;
    this.sharedImportTable = sharedImportTable;
    this.datastore = datastore;
    this.convSlobFacilities = slobFacilities;
    this.convMetadataStore = convMetadataStore;
  }

  /** Returns a nindo converter for conversational wavelets. */
  private Function<Pair<String, Nindo>, Nindo> getConvNindoConverter(
      final Map<String, AttachmentId> attachmentIdMapping) {
    return new Function<Pair<String, Nindo>, Nindo>() {
      @Override public Nindo apply(Pair<String, Nindo> in) {
        String documentId = in.getFirst();
        Nindo.Builder out = new Nindo.Builder();
        in.getSecond().apply(
            // StripWColonFilter must be before AttachmentIdConverter.
            new StripWColonFilter(
                new FixLinkAnnotationsFilter(
                    new PrivateReplyAnchorLegacyIdConverter(documentId,
                        new AttachmentIdConverter(attachmentIdMapping,
                            out)))));
        return out.build();
      }
    };
  }

  private String convertGooglewaveToGmail(String participantId) {
    return participantId.replace("@googlewave.com", "@gmail.com");
  }

  private Pair<GoogleWavelet, ImmutableList<GoogleDocument>> convertGooglewaveToGmail(
      Pair<GoogleWavelet, ? extends List<GoogleDocument>> pair) {
    GoogleWavelet w = pair.getFirst();
    List<GoogleDocument> docs = pair.getSecond();
    GoogleWavelet.Builder w2 = GoogleWavelet.newBuilder(w);
    w2.setCreator(convertGooglewaveToGmail(w.getCreator()));
    w2.clearParticipant();
    for (String p : w.getParticipantList()) {
      w2.addParticipant(convertGooglewaveToGmail(p));
    }
    ImmutableList.Builder<GoogleDocument> docs2 = ImmutableList.builder();
    for (GoogleDocument doc : docs) {
      GoogleDocument.Builder doc2 = GoogleDocument.newBuilder(doc);
      doc2.setAuthor(convertGooglewaveToGmail(doc.getAuthor()));
      doc2.clearContributor();
      for (String p : doc.getContributorList()) {
        doc2.addContributor(convertGooglewaveToGmail(p));
      }
      docs2.add(doc2.build());
    }
    return Pair.of(w2.build(), docs2.build());
  }

  private List<WaveletOperation> convertConvHistory(List<WaveletOperation> in,
      Map<String, AttachmentId> attachmentIdMapping) {
    List<WaveletOperation> out = Lists.newArrayList();
    WaveletHistoryConverter m = new WaveletHistoryConverter(
        getConvNindoConverter(attachmentIdMapping));
    for (WaveletOperation op : in) {
      out.add(m.convertAndApply(op));
    }
    return out;
  }

  private Map<String, AttachmentId> buildAttachmentMapping(ImportWaveletTask task) {
    ImmutableMap.Builder<String, AttachmentId> out = ImmutableMap.builder();
    for (ImportedAttachment attachment : task.getAttachment()) {
      if (attachment.hasLocalId()) {
        out.put(attachment.getRemoteId(), new AttachmentId(attachment.getLocalId()));
      }
    }
    return out.build();
  }

  // Example attachment metadata document:
  // document id: attach+foo
  // begin doc
  //   <node key="upload_progress" value="678"></node>
  //   <node key="attachment_size" value="678"></node>
  //   <node key="mime_type" value="application/octet-stream"></node>
  //   <node key="filename" value="the-file-name"></node>
  //   <node key="attachment_url" value="/attachment/the-file-name?id=a-short-id&key=a-long-key"></node>
  // end doc
  private Map<String, String> makeMapFromDocument(GoogleDocumentContent doc,
      String elementType, String keyAttributeName, String valueAttributeName) {
    Preconditions.checkNotNull(elementType, "Null elementType");
    Preconditions.checkNotNull(keyAttributeName, "Null keyAttributeName");
    Preconditions.checkNotNull(valueAttributeName, "Null valueAttributeName");
    Map<String, String> out = Maps.newHashMap();
    for (Component component : doc.getComponentList()) {
      if (component.hasElementStart()) {
        ElementStart start = component.getElementStart();
        Assert.check(elementType.equals(start.getType()), "Unexpected element type: %s", doc);
        Map<String, String> attrs = Maps.newHashMap();
        for (KeyValuePair attr : start.getAttributeList()) {
          attrs.put(attr.getKey(), attr.getValue());
        }
        Assert.check(attrs.size() == 2, "Need two attrs: %s", doc);
        String key = attrs.get(keyAttributeName);
        String value = attrs.get(valueAttributeName);
        Assert.check(key != null, "Key not found: %s", doc);
        Assert.check(value != null, "Value not found: %s", doc);
        // If already exists, overwrite.  Last entry wins.  TODO(ohler): confirm that this is
        // consistent with how the documents are interpreted by Google Wave.
        out.put(key, value);
      }
    }
    return out;
  }

  private void populateAttachmentInfo(FetchAttachmentsAndImportWaveletTask newTask,
      List<GoogleDocument> documentList, Map<String, GoogleDocument> attachmentDocs) {
    for (Map.Entry<String, GoogleDocument> entry : attachmentDocs.entrySet()) {
      String attachmentId = entry.getKey();
      GoogleDocumentContent metadataDoc = entry.getValue().getContent();
      log.info("metadataDoc=" + metadataDoc);
      Map<String, String> map = makeMapFromDocument(metadataDoc, "node", "key", "value");
      log.info("Attachment metadata for " + attachmentId + ": " + map + ")");
      RemoteAttachmentInfo info = new RemoteAttachmentInfoGsonImpl();
      info.setRemoteId(attachmentId);
      if (map.get("attachment_url") == null) {
        log.warning("Attachment " + attachmentId + " has no URL (incomplete upload?), skipping: "
            + map);
        continue;
      }
      info.setPath(map.get("attachment_url"));
      if (map.get("filename") != null) {
        info.setFilename(map.get("filename"));
      }
      if (map.get("mime_type") != null) {
        info.setMimeType(map.get("mime_type"));
      }
      if (map.get("size") != null) {
        info.setSizeBytes(Long.parseLong(map.get("size")));
      }
      newTask.addToImport(info);
    }
  }

  private Map<String, GoogleDocument> getAttachmentDocs(List<GoogleDocument> docs) {
    Map<String, GoogleDocument> out = Maps.newHashMap();
    for (GoogleDocument doc : docs) {
      String docId = doc.getDocumentId();
      Assert.check(!out.containsKey(docId), "Duplicate doc id %s: %s", docId, docs);
      if (IdUtil.isAttachmentDataDocument(docId)) {
        String[] components = IdUtil.split(docId);
        if (components == null) {
          throw new RuntimeException("Failed to split attachment doc id: " + docId);
        }
        if (components.length != 2) {
          throw new RuntimeException("Bad number of components in attachment doc id " + docId
              + ": " + Arrays.toString(components));
        }
        if (!IdConstants.ATTACHMENT_METADATA_PREFIX.equals(components[0])) {
          throw new RuntimeException("Bad first component in attachment doc id " + docId
              + ": " + Arrays.toString(components));
        }
        String attachmentId = components[1];
        out.put(attachmentId, doc);
      }
    }
    return ImmutableMap.copyOf(out);
  }

  private static class TaskCompleted extends Exception {
    private static final long serialVersionUID = 623496132804869626L;

    private final List<ImportTaskPayload> followupTasks;

    public TaskCompleted(List<ImportTaskPayload> followupTasks) {
      this.followupTasks = ImmutableList.copyOf(followupTasks);
    }

    public static TaskCompleted noFollowup() {
      return new TaskCompleted(ImmutableList.<ImportTaskPayload>of());
    }

    public static TaskCompleted withFollowup(ImportTaskPayload... followupTasks) {
      return new TaskCompleted(ImmutableList.copyOf(followupTasks));
    }

    public static TaskCompleted withFollowup(ImportWaveletTask followupTask) {
      ImportTaskPayload payload = new ImportTaskPayloadGsonImpl();
      payload.setImportWaveletTask(followupTask);
      return TaskCompleted.withFollowup(payload);
    }
  }

  private class ImportContext {
    private final ImportWaveletTask task;
    private final SourceInstance instance;
    private final WaveletName waveletName;
    private final ImportSharingMode sharingMode;
    private final RobotApi robotApi;

    private ImportContext(ImportWaveletTask task,
        SourceInstance instance,
        WaveletName waveletName,
        ImportSharingMode sharingMode,
        RobotApi robotApi) {
      this.task = checkNotNull(task, "Null task");
      this.instance = checkNotNull(instance, "Null instance");
      this.waveletName = checkNotNull(waveletName, "Null waveletName");
      this.sharingMode = checkNotNull(sharingMode, "Null sharingMode");
      this.robotApi = checkNotNull(robotApi, "Null robotApi");
    }

    private List<WaveletOperation> handleAttachmentsAndSynthesizeHistory(
        Pair<GoogleWavelet, ImmutableList<GoogleDocument>> snapshot)
        throws TaskCompleted {
      GoogleWavelet wavelet = snapshot.getFirst();
      List<GoogleDocument> documents = snapshot.getSecond();
      log.info("Snapshot for " + waveletName + ": "
          + wavelet.getParticipantCount() + " participants, "
          + documents.size() + " documents");
      log.info("Document ids: " + Lists.transform(documents,
              new Function<GoogleDocument, String>() {
                @Override public String apply(GoogleDocument doc) { return doc.getDocumentId(); }
              }));
      // Maps attachment ids (not document ids) to documents.
      Map<String, GoogleDocument> attachmentDocs = getAttachmentDocs(documents);
      Map<String, AttachmentId> attachmentMapping;
      if (attachmentDocs.isEmpty()) {
        log.info("No attachments");
        attachmentMapping = ImmutableMap.of();
      } else if (task.getAttachmentSize() > 0) {
        attachmentMapping = buildAttachmentMapping(task);
        log.info("Attachments already imported; importing with attachment mapping "
            + attachmentMapping);
      } else {
        log.info("Found attachmend ids " + attachmentDocs.keySet());
        // Replace this task with one that fetches attachments and then imports.
        FetchAttachmentsAndImportWaveletTask newTask =
            new FetchAttachmentsAndImportWaveletTaskGsonImpl();
        newTask.setOriginalImportTask(task);
        populateAttachmentInfo(newTask, documents, attachmentDocs);
        ImportTaskPayload payload = new ImportTaskPayloadGsonImpl();
        payload.setFetchAttachmentsTask(newTask);
        throw TaskCompleted.withFollowup(payload);
      }
      List<WaveletOperation> history =
          new HistorySynthesizer().synthesizeHistory(wavelet, documents);
      return convertConvHistory(history, attachmentMapping);
    }

    private void addToPerUserTable(CheckedTransaction tx, SlobId newId)
        throws RetryableFailure, PermanentFailure {
      @Nullable RemoteConvWavelet entry =
          perUserTable.getWavelet(tx, userId, instance, waveletName);
      if (entry == null) {
        log.warning("No per-user entry for " + waveletName + ", can't add " + newId);
        return;
      }
      // We overwrite unconditionally here, since it's a corner case.
      // TODO(ohler): think about this and explain what exactly is going on.
      switch (sharingMode) {
        case PRIVATE:
          entry.setPrivateLocalId(newId);
          break;
        case SHARED:
          entry.setSharedLocalId(newId);
          break;
        default:
          throw new AssertionError("Unexpected ImportSharingMode: " + sharingMode);
      }
      perUserTable.putWavelet(tx, userId, entry);
    }

    private void addToPerUserTableWithoutTx(final SlobId newId) throws PermanentFailure {
      new RetryHelper().run(
          new RetryHelper.VoidBody() {
            @Override public void run() throws RetryableFailure, PermanentFailure {
              CheckedTransaction tx = datastore.beginTransaction();
              try {
                addToPerUserTable(tx, newId);
                tx.commit();
              } finally {
                tx.close();
              }
            }
          });
    }

    // Returns false iff already unlocked.
    private boolean unlockWavelet(CheckedTransaction tx, SlobId convId)
        throws RetryableFailure, PermanentFailure {
      ConvMetadataGsonImpl metadata = convMetadataStore.get(tx, convId);
      Assert.check(metadata.hasImportMetadata(), "%s: Metadata has no import: %s",
          convId, metadata);
      if (metadata.getImportMetadata().getImportFinished()) {
        log.info(convId + ": already unlocked");
        return false;
      }
      ImportMetadata importMetadata = metadata.getImportMetadata();
      importMetadata.setImportFinished(true);
      metadata.setImportMetadata(importMetadata);
      log.info(convId + ": unlocking");
      convMetadataStore.put(tx, convId, metadata);
      return true;
    }

    private ClientId getFakeClientId() {
      return new ClientId("");
    }

    private void ensureParticipant(final SlobId convId) throws PermanentFailure {
      new RetryHelper().run(
          new RetryHelper.VoidBody() {
            @Override public void run() throws RetryableFailure, PermanentFailure {
              CheckedTransaction tx = datastore.beginTransaction();
              try {
                ConvMetadata metadata = convMetadataStore.get(tx, convId);
                Assert.check(metadata.hasImportMetadata(), "%s: Metadata has no import: %s",
                    convId, metadata);
                Assert.check(metadata.getImportMetadata().getImportFinished(),
                    "%s: still importing: %s", convId, metadata);
                MutationLog l = convSlobFacilities.getMutationLogFactory().create(tx, convId);
                StateAndVersion stateAndVersion = l.reconstruct(null);
                Assert.check(stateAndVersion.getVersion() > 0, "%s at version 0: %s",
                    convId, stateAndVersion);
                // TODO(ohler): use generics to avoid the cast
                ReadableWaveletObject state = (ReadableWaveletObject) stateAndVersion.getState();
                if (state.getParticipants().contains(importingUser)) {
                  log.info(importingUser + " is a participant at version "
                      + stateAndVersion.getVersion());
                  return;
                }
                WaveletOperation op =
                    HistorySynthesizer.newAddParticipant(importingUser.getAddress(),
                        // We preserve last modified time to avoid re-ordering people's inboxes
                        // just because another participant imported.
                        state.getLastModifiedMillis(),
                        importingUser.getAddress());
                log.info(importingUser + " is not a participant at version "
                    + stateAndVersion.getVersion() + ", adding " + op);
                MutationLog.Appender appender = l.prepareAppender().getAppender();
                try {
                  appender.append(
                      new ChangeData<String>(getFakeClientId(), SERIALIZER.serializeDelta(op)));
                } catch (ChangeRejected e) {
                  throw new RuntimeException("Appender rejected AddParticipant: " + op);
                }
                // TODO(ohler): Share more code with LocalMutationProcessor; having to call this
                // stuff here rather than just commit() is error-prone.
                appender.finish();
                convSlobFacilities.getLocalMutationProcessor().runPreCommit(tx, convId, appender);
                convSlobFacilities.getLocalMutationProcessor().schedulePostCommit(
                    tx, convId, appender);
                tx.commit();
              } finally {
                tx.close();
              }
            }
          });
    }

    private void doImport() throws TaskCompleted, PermanentFailure, IOException {
      // Look up if already imported according to PerUserTable; if so, we have nothing to do.
      boolean alreadyImportedForThisUser = new RetryHelper().run(
          new RetryHelper.Body<Boolean>() {
            @Override public Boolean run() throws RetryableFailure, PermanentFailure {
              CheckedTransaction tx = datastore.beginTransaction();
              try {
                @Nullable RemoteConvWavelet entry =
                    perUserTable.getWavelet(tx, userId, instance, waveletName);
                switch (sharingMode) {
                  case PRIVATE:
                    if (entry != null && entry.getPrivateLocalId() != null
                        && !(task.hasExistingSlobIdToIgnore()
                            && new SlobId(task.getExistingSlobIdToIgnore()).equals(
                                entry.getPrivateLocalId()))) {
                      log.info("Private import already exists, aborting: " + entry);
                      return true;
                    } else {
                      return false;
                    }
                  case SHARED:
                    if (entry != null && entry.getSharedLocalId() != null
                        && !(task.hasExistingSlobIdToIgnore()
                            && new SlobId(task.getExistingSlobIdToIgnore()).equals(
                                entry.getSharedLocalId()))) {
                      log.info("Shared import already exists, aborting: " + entry);
                      return true;
                    } else {
                      return false;
                    }
                  default: throw new AssertionError("Unexpected ImportSharingMode: " + sharingMode);
                }
              } finally {
                tx.close();
              }
            }
          });
      if (alreadyImportedForThisUser) {
        throw TaskCompleted.noFollowup();
      }
      // Fetch the wavelet.  Even if this is a shared import and the wavelet is
      // already imported, we have to do this fetch before re-using the existing
      // wavelet, to confirm that the user has access to the wavelet on the remote
      // instance.
      Pair<GoogleWavelet, ImmutableList<GoogleDocument>> snapshot =
          convertGooglewaveToGmail(robotApi.getSnapshot(waveletName));
      GoogleWavelet wavelet = snapshot.getFirst();
      log.info("Snapshot fetch succeeded: version " + wavelet.getVersion() + ", "
          + wavelet.getParticipantCount() + " participants: "  + wavelet.getParticipantList());
      switch (sharingMode) {
        case PRIVATE: break;
        case SHARED:
          @Nullable SlobId existingSharedImport =
              sharedImportTable.lookupWithoutTx(instance, waveletName);
          if (existingSharedImport != null
              && !(task.hasExistingSlobIdToIgnore()
                  && new SlobId(task.getExistingSlobIdToIgnore()).equals(existingSharedImport))) {
            log.info("Found existing shared import " + existingSharedImport + ", re-using");
            ensureParticipant(existingSharedImport);
            addToPerUserTableWithoutTx(existingSharedImport);
            throw TaskCompleted.noFollowup();
          }
          break;
        default: throw new AssertionError("Unexpected ImportSharingMode: " + sharingMode);
      }
      List<WaveletOperation> history = handleAttachmentsAndSynthesizeHistory(snapshot);
      log.info("Synthesized history with " + history.size() + " ops; fixing up participation");
      switch (sharingMode) {
        case PRIVATE:
          for (String participant : ImmutableList.copyOf(wavelet.getParticipantList())) {
            history.add(
                HistorySynthesizer.newRemoveParticipant(importingUser.getAddress(),
                    wavelet.getLastModifiedTimeMillis(), participant));
          }
          history.add(
              HistorySynthesizer.newAddParticipant(importingUser.getAddress(),
                  wavelet.getLastModifiedTimeMillis(), importingUser.getAddress()));
          break;
        case SHARED:
          if (!wavelet.getParticipantList().contains(importingUser.getAddress())) {
            log.info(
                importingUser + " is not a participant, adding: " + wavelet.getParticipantList());
            history.add(
                HistorySynthesizer.newAddParticipant(importingUser.getAddress(),
                    wavelet.getLastModifiedTimeMillis(), importingUser.getAddress()));
          }
          break;
        default:
          throw new AssertionError("Unexpected ImportSharingMode: " + sharingMode);
      }
      log.info("History now has " + history.size() + " ops");
      ImportMetadata importMetadata = new ImportMetadataGsonImpl();
      importMetadata.setImportBeginTimeMillis(System.currentTimeMillis());
      importMetadata.setImportFinished(false);
      importMetadata.setOriginalImporter(userId.getId());
      importMetadata.setSourceInstance(instance.serialize());
      importMetadata.setRemoteWaveId(waveletName.waveId.serialise());
      importMetadata.setRemoteWaveletId(waveletName.waveletId.serialise());
      ConvMetadataGsonImpl convMetadata = new ConvMetadataGsonImpl();
      convMetadata.setImportMetadata(importMetadata);
      final SlobId newId = waveletCreator.newConvWithGeneratedId(history, convMetadata, true);
      log.info("Imported wavelet " + waveletName + " as local id " + newId);
      boolean abandonAndRetry = new RetryHelper().run(
          new RetryHelper.Body<Boolean>() {
            @Override public Boolean run() throws RetryableFailure, PermanentFailure {
              CheckedTransaction tx = datastore.beginTransactionXG();
              try {
                switch (sharingMode) {
                  case SHARED:
                    @Nullable SlobId existingSharedImport =
                        sharedImportTable.lookup(tx, instance, waveletName);
                    if (existingSharedImport != null
                        && !(task.hasExistingSlobIdToIgnore()
                            && new SlobId(task.getExistingSlobIdToIgnore()).equals(
                                existingSharedImport))) {
                      log.warning("Found existing shared import " + existingSharedImport
                          + ", abandoning import and retrying");
                      return true;
                    }
                    sharedImportTable.put(tx, instance, waveletName, newId);
                    break;
                  case PRIVATE:
                    break;
                  default:
                    throw new AssertionError("Unexpected ImportSharingMode: " + sharingMode);
                }
                if (!unlockWavelet(tx, newId)) {
                  // Already unlocked, which means this transaction is a spurious retry.
                  // Nothing to do.
                  return false;
                }
                addToPerUserTable(tx, newId);
                // We don't want to run the immediate post-commit actions in this task
                // since we don't want this task to fail if they crash.  So we
                // just schedule a task unconditionally.
                //
                // TODO(ohler): Decide what to do about pre-commit actions.  Maybe we should
                // run them here?
                convSlobFacilities.getPostCommitActionScheduler()
                    .unconditionallyScheduleTask(tx, newId);
                tx.commit();
                return false;
              } finally {
                tx.close();
              }
            }
          });
      if (abandonAndRetry) {
        throw TaskCompleted.withFollowup(task);
      }
      log.info("Completed");
      throw TaskCompleted.noFollowup();
    }
  }

  public List<ImportTaskPayload> importWavelet(ImportWaveletTask task)
      throws IOException, PermanentFailure {
    SourceInstance instance = sourceInstanceFactory.parseUnchecked(task.getInstance());
    try {
      new ImportContext(task, instance,
          WaveletName.of(
              WaveId.deserialise(task.getWaveId()),
              WaveletId.deserialise(task.getWaveletId())),
          task.getSharingMode(),
          robotApiFactory.create(instance.getApiUrl()))
          .doImport();
      throw new AssertionError("import() did not throw TaskCompleted");
    } catch (TaskCompleted e) {
      return e.followupTasks;
    }
  }

}
