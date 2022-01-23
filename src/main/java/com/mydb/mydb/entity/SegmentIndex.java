package com.mydb.mydb.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SegmentIndex implements Serializable {

  private static final long serialVersionUID = 5388380270261334686L;

  private String segmentName;

  private Map<String, SegmentMetadata> index;
}
