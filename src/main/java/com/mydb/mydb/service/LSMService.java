package com.mydb.mydb.service;

import com.mydb.mydb.entity.MemTableWrapper;
import com.mydb.mydb.entity.SegmentIndex;
import com.mydb.mydb.exception.DeplomaticUntilReinforcements;
import com.mydb.mydb.exception.UnknownProbeException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.SerializationUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
public class LSMService {

  public static boolean hardLimitReached = false;
  private final FileIOService fileIOService;
  private final SegmentService segmentService;
  private final MergeService mergeService;
  private final Deque<SegmentIndex> indices;
  private final MemTableWrapper memTable;

  @Autowired
  public LSMService(MemTableWrapper memTable,
                    @Qualifier("indices") Deque<SegmentIndex> indices, FileIOService fileIOService,
                    SegmentService segmentService, MergeService mergeService
  ) {
    this.fileIOService = fileIOService;
    this.segmentService = segmentService;
    this.mergeService = mergeService;
    this.indices = indices;
    this.memTable = memTable;
  }

  @Scheduled(initialDelay = 10000, fixedDelay = 30000)
  public void merge() throws IOException {
//    log.info("**************\nStarting scheduled merging!\n******************");
    final var segmentEnumeration = getSegmentIndexEnumeration();
    var segmentIndexCountToBeRemoved = segmentEnumeration.size();
    var mergeSegment = segmentService.getNewSegment();
    final var validSegmentEnumeration = segmentEnumeration.stream()
        .filter(i -> new File(segmentService.getPathForSegment(i.getRight().getSegment().getSegmentName())).exists())
        .toList();
    if (validSegmentEnumeration.size() > 1) {
      var mergedSegmentIndex = mergeService.merge(validSegmentEnumeration, mergeSegment.getSegmentPath());

      indices.addLast(new SegmentIndex(mergeSegment, mergedSegmentIndex));
      IntStream.range(0, segmentIndexCountToBeRemoved)
          .forEach(x -> indices.removeAll(getIndicesForMergedSegments(validSegmentEnumeration)));

      fileIOService.persistIndices(mergeSegment.getBackupPath(), SerializationUtils.serialize(indices));
      deleteMergedSegments(segmentEnumeration);
    }
  }

  private List<SegmentIndex> getIndicesForMergedSegments(
      List<ImmutablePair<Enumeration<String>, SegmentIndex>> segmentEnumeration) {
    return segmentEnumeration.stream().map(ImmutablePair::getRight).collect(Collectors.toList());
  }

  private void deleteMergedSegments(
      final List<ImmutablePair<Enumeration<String>, SegmentIndex>> segmentIndexEnumeration) {
    segmentIndexEnumeration.parallelStream().map(x -> x.getRight().getSegment())
        .forEach(segment -> {
          try {
            new File(segment.getBackupPath()).delete();
            new File(segment.getSegmentPath()).delete();
          } catch (RuntimeException exception) {
            exception.printStackTrace();
          }
        });
  }

  public List<ImmutablePair<Enumeration<String>, SegmentIndex>> getSegmentIndexEnumeration() {
    return indices.stream()
        .map(j -> ImmutablePair.of(Collections.enumeration(j.getSegmentIndex().keySet()), j)).toList();
  }

  public CompletableFuture<Boolean> insert(final String probeId, final String payload) {
    if (hardLimitReached) {
      throw new DeplomaticUntilReinforcements("All write requests will be ignored " +
          "until memory becomes available!");
    }
    return memTable.persist(probeId, payload);
  }

  public String getData(String probeId) throws UnknownProbeException {
    var data = memTable.get(probeId);
    if (data == null) {
      var dataFromSegments = getDataFromSegments(probeId);
      if (dataFromSegments == null) {
        throw new UnknownProbeException(String.format("Probe id - %s not found!", probeId));
      }
      return dataFromSegments;
    }
    return data;
  }

  private String getDataFromSegments(final String probeId) {

    var segmentIndex = indices.stream().filter(x -> x.getSegmentIndex().containsKey(probeId)).findFirst().orElse(null);
    return Optional.ofNullable(segmentIndex)
        .map(i -> segmentService.getPathForSegment(i.getSegment().getSegmentName()))
        .map(p -> fileIOService.getPayload(p, segmentIndex.getSegmentIndex().get(probeId)))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .orElse(null);
  }
}
