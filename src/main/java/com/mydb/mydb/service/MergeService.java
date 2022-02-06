package com.mydb.mydb.service;

import com.mydb.mydb.entity.SegmentIndex;
import com.mydb.mydb.entity.SegmentMetadata;
import com.mydb.mydb.entity.merge.HeapElement;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import static com.mydb.mydb.MydbApplication.MAX_MEM_TABLE_SIZE;
import static com.mydb.mydb.entity.merge.HeapElement.getHeapElementComparator;
import static com.mydb.mydb.entity.merge.HeapElement.isProbeIdPresentInList;

@Slf4j
@Component
public class MergeService {

  private final FileIOService fileIOService;
  private final SegmentService segmentService;

  @Autowired
  public MergeService(FileIOService fileIOService, SegmentService segmentService) {
    this.fileIOService = fileIOService;
    this.segmentService = segmentService;
  }

  public List<SegmentIndex> merge(
      List<ImmutablePair<Enumeration<String>, SegmentIndex>> segmentIndexEnumeration)
      throws IOException {
    var mergeSegment = segmentService.getNewSegment();
    var mergeSegmentFile = new File(mergeSegment.getSegmentPath());
    var segmentIndices = new LinkedList<SegmentIndex>();
    Map<String, SegmentMetadata> mergedSegmentIndex = new LinkedHashMap<>();
    var heap = new PriorityQueue<>(getHeapElementComparator());

    heap.addAll(getSeedElements(segmentIndexEnumeration));

    var last = new HeapElement("", -1);
    while (!heap.isEmpty()) {
      var candidate = findNextCandidate(segmentIndexEnumeration, heap, last);
      if (couldNotFindNextElementToProcess(heap, last, candidate)) {
        break;
      }
      var segmentIndex = segmentIndexEnumeration.get(candidate.getIndex()).right;
      var in = fileIOService.readBytes(
          segmentIndex.getSegment().getSegmentPath(),
          segmentIndex.getSegmentIndex().get(candidate.getProbeId())
      );
      mergedSegmentIndex.put(candidate.getProbeId(), new SegmentMetadata(mergeSegmentFile.length(), in.length));
      FileUtils.writeByteArrayToFile(mergeSegmentFile, in, true);

      if(mergedSegmentIndex.size() >= MAX_MEM_TABLE_SIZE) {
        segmentIndices.add(new SegmentIndex(mergeSegment, mergedSegmentIndex));
        mergedSegmentIndex =  new LinkedHashMap<String, SegmentMetadata>();
        mergeSegment = segmentService.getNewSegment();
      }
      addNextHeapElementForSegment(segmentIndexEnumeration, heap, candidate);
      last = candidate;
    }
    return segmentIndices;
  }

  private HeapElement findNextCandidate(List<ImmutablePair<Enumeration<String>, SegmentIndex>> segmentIndexEnumeration,
                                        PriorityQueue<HeapElement> heap, HeapElement last) {
    var next = heap.remove();
    while (next.getProbeId().equals(last.getProbeId())) {
      addNextHeapElementForSegment(segmentIndexEnumeration, heap, next);
      if (heap.isEmpty()) {
        break;
      }
      next = heap.remove();
    }
    return next;
  }

  private boolean couldNotFindNextElementToProcess(PriorityQueue<HeapElement> heap, HeapElement last,
                                                   HeapElement next) {
    return heap.isEmpty() && next.getProbeId().equals(last.getProbeId());
  }

  private void addNextHeapElementForSegment(
      List<ImmutablePair<Enumeration<String>, SegmentIndex>> segmentIndexEnumeration,
      PriorityQueue<HeapElement> heap, HeapElement next) {
    var iterator = segmentIndexEnumeration.get(next.getIndex()).left;
    if (iterator.hasMoreElements()) {
      heap.add(new HeapElement(iterator.nextElement(), next.getIndex()));
    }
  }

  private LinkedList<HeapElement> getSeedElements(
      List<ImmutablePair<Enumeration<String>, SegmentIndex>> segmentIndexEnumeration) {
    var firstElements = new LinkedList<HeapElement>();
    int itr = 0;
    while (itr < segmentIndexEnumeration.size()) {
      var next = new HeapElement(segmentIndexEnumeration.get(itr).left.nextElement(), itr);
      while (isProbeIdPresentInList(firstElements, next.getProbeId())) {
        if (segmentIndexEnumeration.get(itr).left.hasMoreElements()) {
          next = new HeapElement(segmentIndexEnumeration.get(itr).left.nextElement(), itr);
        } else {
          break;
        }
      }
      if (!isProbeIdPresentInList(firstElements, next.getProbeId())) {
        firstElements.add(next);
      }
      itr += 1;
    }
    return firstElements;
  }

}
