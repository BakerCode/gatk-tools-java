/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

// This file differs from GenomicsConverter in the utils-java package 
// ( com.google.cloud.genomics.utils) and it depends on HTSJDK 1.118.
// utils-java can not yet be moved to depend on HTSJDK due to pending
// work (https://github.com/samtools/htsjdk/pull/55).
// Once this completes and utils-java is updated this file can be removed. 
package com.google.cloud.genomics.gatk.common;

import com.google.api.services.genomics.model.Header;
import com.google.api.services.genomics.model.HeaderSection;
import com.google.api.services.genomics.model.Program;
import com.google.api.services.genomics.model.Read;
import com.google.api.services.genomics.model.ReadGroup;
import com.google.api.services.genomics.model.ReferenceSequence;
import com.google.cloud.genomics.utils.GenomicsUtils;
import com.google.common.collect.Lists;

import htsjdk.samtools.TagValueAndUnsignedArrayFlag;
import htsjdk.samtools.TextTagCodec;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMProgramRecord;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.SAMRecord.SAMTagAndValue;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.xml.bind.DatatypeConverter;

/**
 * A utility class for converting between genomics data representations by the Cloud Genomics API
 * and that of Picard Tools
 * 
 * Notes: Conversion is not perfect, information WILL be lost!
 *        HTSJDK formats get very mad about passing nulls, so lots of null checks. Genomics API 
 *        classes however are fine with nulls so no checks need to be done.
 * 
 * Currently supported conversions:
 *      Read <-> SAMRecord
 *      HeaderSection <-> SAMFileHeader
 */
public abstract class GenomicsConverter {
  private static final Calendar GMT_CALENDAR = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
  
  /** 
   * Standard tags defined in SAM spec. and their types.
   * See http://samtools.github.io/hts-specs/SAMv1.pdf, section 1.5.
   */
  private static Map<String, String> SAM_TAGS;
  
  static {
    SAM_TAGS = new HashMap<String,String>();
    SAM_TAGS.put("AM", "i");
    SAM_TAGS.put("AS", "i");
    SAM_TAGS.put("BC", "Z");
    SAM_TAGS.put("BQ", "Z");
    SAM_TAGS.put("CC", "Z");
    SAM_TAGS.put("CM", "i");
    SAM_TAGS.put("CO", "Z");
    SAM_TAGS.put("CP", "i");
    SAM_TAGS.put("CQ", "Z");
    SAM_TAGS.put("CS", "Z");
    SAM_TAGS.put("CT", "Z");
    SAM_TAGS.put("E2", "Z");
    SAM_TAGS.put("FI", "i");
    SAM_TAGS.put("FS", "Z");
    SAM_TAGS.put("FZ", "B");
    SAM_TAGS.put("H0", "i");
    SAM_TAGS.put("H1", "i");
    SAM_TAGS.put("H2", "i");
    SAM_TAGS.put("HI", "i");
    SAM_TAGS.put("IH", "i");
    SAM_TAGS.put("LB", "Z");
    SAM_TAGS.put("MC", "Z");
    SAM_TAGS.put("MD", "Z");
    SAM_TAGS.put("MQ", "i");
    SAM_TAGS.put("NH", "i");
    SAM_TAGS.put("NM", "i");
    SAM_TAGS.put("OQ", "Z");
    SAM_TAGS.put("OP", "i");
    SAM_TAGS.put("OC", "Z");
    SAM_TAGS.put("PG", "Z");
    SAM_TAGS.put("PQ", "i");
    SAM_TAGS.put("PT", "Z");
    SAM_TAGS.put("PU", "Z");
    SAM_TAGS.put("QT", "Z");
    SAM_TAGS.put("Q2", "Z");
    SAM_TAGS.put("R2", "Z");
    SAM_TAGS.put("RG", "Z");
    SAM_TAGS.put("RT", "Z");
    SAM_TAGS.put("SA", "Z");
    SAM_TAGS.put("SM", "i");
    SAM_TAGS.put("TC", "i");
    SAM_TAGS.put("U2", "Z");
    SAM_TAGS.put("UQ", "i");
  }
  
  /** Returns SAM Tag type. If not a known tag - defaults to "Z". */
  public static String getTagType(String tagName) {
    final String result = SAM_TAGS.get(tagName);
    return result != null ? result : "Z";
  }
  
  /** Codec used for converting know SAM tags and their values, from strings to Objects */
  private static TextTagCodec textTagCodec = new TextTagCodec();
  
  /**
   *  Generates a Read from a SAMRecord. Id, ReadsetId, and AlignedBases fields are lost.
   */
  public static final Read makeRead(SAMRecord record) {
    Read read = new Read();
    read.setName(record.getReadName());
    read.setFlags(record.getFlags());
    read.setReferenceSequenceName(record.getReferenceName());
    read.setPosition(record.getAlignmentStart());
    read.setMappingQuality(record.getMappingQuality());
    read.setCigar(record.getCigarString());
    read.setMateReferenceSequenceName(record.getMateReferenceName());
    read.setMatePosition(record.getMateAlignmentStart());
    read.setTemplateLength(record.getInferredInsertSize());
    read.setOriginalBases(record.getReadString());
    read.setBaseQuality(record.getBaseQualityString());

    if (record.getAttributes() != null) {
      HashMap<String, List<String>> tags = new HashMap<String, List<String>>();
      for(SAMTagAndValue tagPair : record.getAttributes()) {
        if (tags.containsKey(tagPair.tag)) {
          tags.get(tagPair.tag).add(tagPair.value.toString());
        } else {
          tags.put(tagPair.tag, Lists.newArrayList((tagPair.value.toString())));
        }
      }
      read.setTags(tags);
    }

    return read;
  }

  public static final SAMRecord makeSAMRecord(Read read, SAMFileHeader header) {
    SAMRecord record = new SAMRecord(header);
    if (read.getName() != null) {
      record.setReadName(read.getName());
    }
    if (read.getFlags() != null) {
      record.setFlags(read.getFlags());
    }
    if (read.getReferenceSequenceName() != null) {
      record.setReferenceName(read.getReferenceSequenceName());
    }
    if (read.getPosition() != null) {
      record.setAlignmentStart(read.getPosition());
    }
    if (read.getMappingQuality() != null) {
      record.setMappingQuality(read.getMappingQuality());
    }
    if (read.getCigar() != null) {
      record.setCigarString(read.getCigar());
    }
    if (read.getMateReferenceSequenceName() != null) {
      record.setMateReferenceName(read.getMateReferenceSequenceName());
    }
    if (read.getMatePosition() != null) {
      record.setMateAlignmentStart(read.getMatePosition());
    }
    if (read.getTemplateLength() != null) {
      record.setInferredInsertSize(read.getTemplateLength());
    }
    if (read.getOriginalBases() != null) {
      record.setReadString(read.getOriginalBases());
    }
    if (read.getBaseQuality() != null) {
      record.setBaseQualityString(read.getBaseQuality());
    }

    Map<String, List<String>> tags = read.getTags();
    if (tags != null) {
      for (String tag : tags.keySet()) {
        List<String> values = tags.get(tag);
        if (values != null) {
          for (String value : values) {
              Object attrValue = textTagCodec.decode(
                  tag + ":" + getTagType(tag) + ":" + value)
                  .getValue();
                  if (attrValue instanceof TagValueAndUnsignedArrayFlag) {
                    record.setUnsignedArrayAttribute(tag, 
                        ((TagValueAndUnsignedArrayFlag)attrValue).value);
                  } else {
                    record.setAttribute(tag, attrValue);
                  }
          }
        }
      }
    }

    return record;
  }

  public static final SAMRecord makeSAMRecord(Read read, HeaderSection header) {
    return makeSAMRecord(read, makeSAMFileHeader(header));
  }

  public static final SAMRecord makeSAMRecord(Read read) {
    return makeSAMRecord(read, new SAMFileHeader());
  }

  /**
   * Generates a HeaderSection from a SAMFileHeader.
   * Lost fields:
   * FileUri, RefSequences.md5Checksum, RefSequences.uri, ReadGroups.ProcessingProgram,
   * ReadGroups.ReadGroupId, ProgramRecords.ProgramGroupId, ReferenceSequence.uri, etc
   */
  public static final HeaderSection makeHeaderSection(SAMFileHeader samHeader) {
    HeaderSection header = new HeaderSection();

    Header HD = new Header();
    HD.setVersion(samHeader.getVersion());
    HD.setSortingOrder(samHeader.getSortOrder().toString());
    header.setHeaders(Lists.newArrayList(HD));

    if (samHeader.getSequenceDictionary() != null 
        && samHeader.getSequenceDictionary().getSequences() != null) {
      List<ReferenceSequence> SQList = Lists.newArrayList();
      for (SAMSequenceRecord sequence : samHeader.getSequenceDictionary().getSequences()) {
        ReferenceSequence SQ = new ReferenceSequence();
        SQ.setAssemblyId(sequence.getAssembly());
        SQ.setLength(sequence.getSequenceLength());
        SQ.setName(sequence.getSequenceName());
        SQ.setSpecies(sequence.getSpecies());
        SQList.add(SQ);
      }
      header.setRefSequences(SQList);
    }

    if (samHeader.getReadGroups() != null) {
      List<ReadGroup> RGList = Lists.newArrayList();
      for (SAMReadGroupRecord readgroup : samHeader.getReadGroups()) {
        ReadGroup RG = new ReadGroup();
        if(readgroup.getRunDate() != null) {
          Calendar calendar = GMT_CALENDAR;
          calendar.setTime(readgroup.getRunDate());
          RG.setDate(DatatypeConverter.printDateTime(calendar));
        }
        RG.setDescription(readgroup.getDescription());
        RG.setFlowOrder(readgroup.getFlowOrder());
        RG.setId(readgroup.getId());
        RG.setKeySequence(readgroup.getKeySequence());
        RG.setLibrary(readgroup.getLibrary());
        RG.setPlatformUnit(readgroup.getPlatformUnit());
        RG.setPredictedInsertSize(readgroup.getPredictedMedianInsertSize());
        RG.setSample(readgroup.getSample());
        RG.setSequencingCenterName(readgroup.getSequencingCenter());
        RG.setSequencingTechnology(readgroup.getPlatform());
        RGList.add(RG);
      }
      header.setReadGroups(RGList);
    }

    if (samHeader.getProgramRecords() != null) {
      List<Program> PGList = Lists.newArrayList();
      for (SAMProgramRecord program : samHeader.getProgramRecords()) {
        Program PG = new Program();
        PG.setCommandLine(program.getCommandLine());
        PG.setId(program.getProgramGroupId());
        PG.setName(program.getProgramName());
        PG.setPrevProgramId(program.getPreviousProgramGroupId());
        PG.setVersion(program.getProgramVersion());
        PGList.add(PG);
      }
      header.setPrograms(PGList);
    }

    if (samHeader.getComments() != null) {
      List<String> COList = Lists.newArrayList(); 
      for (String comment : samHeader.getComments()) {
        // SAMFileHeader makes all comments have a @CO\t prefix
        COList.add(comment.replaceAll("@CO\t", ""));
      }
      header.setComments(COList);
    }

    return header;
  }

  /**
   * Genereates a SAMFileHeader from a HeaderSection.
   * NOTE: Version field is not setable and @CO\t is prepended to all comments
   */
  public static final SAMFileHeader makeSAMFileHeader(HeaderSection header) {
    SAMFileHeader samHeader = new SAMFileHeader();

    Header HD = GenomicsUtils.getHeader(header);
    if (HD != null && HD.getSortingOrder() != null) {
      samHeader.setSortOrder(SAMFileHeader.SortOrder.valueOf(HD.getSortingOrder()));
    }

    if (header.getRefSequences() != null) {
      SAMSequenceDictionary dict = new SAMSequenceDictionary();
      for (ReferenceSequence SQ : header.getRefSequences()) {
        if (SQ.getName() != null && SQ.getLength() != null) {
          SAMSequenceRecord sequence = new SAMSequenceRecord(SQ.getName(), SQ.getLength());
          if (SQ.getAssemblyId() != null) {
            sequence.setAssembly(SQ.getAssemblyId());
          }
          if (SQ.getSpecies() != null) {
            sequence.setSpecies(SQ.getSpecies());
          }
          dict.addSequence(sequence);
        }
      }
      samHeader.setSequenceDictionary(dict);
    }

    if (header.getReadGroups() != null) {
      List<SAMReadGroupRecord> readgroups = Lists.newArrayList();
      for (ReadGroup RG : header.getReadGroups()) {
        if (RG.getId() != null) {
          SAMReadGroupRecord readgroup = new SAMReadGroupRecord(RG.getId());
          if (RG.getDate() != null) {
            readgroup.setRunDate(DatatypeConverter.parseDateTime(RG.getDate()).getTime());
          }
          if (RG.getDescription() != null) {
            readgroup.setDescription(RG.getDescription());
          }
          if (RG.getFlowOrder() != null) {
            readgroup.setFlowOrder(RG.getFlowOrder());
          }
          if (RG.getKeySequence() != null) {
            readgroup.setKeySequence(RG.getKeySequence());
          }
          if (RG.getLibrary() != null) {
            readgroup.setLibrary(RG.getLibrary());
          }
          if (RG.getPlatformUnit() != null) {
            readgroup.setPlatformUnit(RG.getPlatformUnit());
          }
          if (RG.getPredictedInsertSize() != null) {
            readgroup.setPredictedMedianInsertSize(RG.getPredictedInsertSize());
          }
          if (RG.getSample() != null) {
            readgroup.setSample(RG.getSample());
          }
          if (RG.getSequencingCenterName() != null) {
            readgroup.setSequencingCenter(RG.getSequencingCenterName());
          }
          if (RG.getSequencingTechnology() != null) {
            readgroup.setPlatform(RG.getSequencingTechnology());
          }
          readgroups.add(readgroup);
        }
      }
      samHeader.setReadGroups(readgroups);
    }

    if (header.getPrograms() != null) {
      List<SAMProgramRecord> programs = Lists.newArrayList();
      for (Program PG : header.getPrograms()) {
        if (PG.getId() != null) {
          SAMProgramRecord program = new SAMProgramRecord(PG.getId());
          if (PG.getCommandLine() != null) {
            program.setCommandLine(PG.getCommandLine());
          }
          if (PG.getName() != null) {
            program.setProgramName(PG.getName());
          }
          if (PG.getPrevProgramId() != null) {
            program.setPreviousProgramGroupId(PG.getPrevProgramId());
          }
          if (PG.getVersion() != null) {
            program.setProgramVersion(PG.getVersion());
          }
          programs.add(program);
        }
      }
      samHeader.setProgramRecords(programs);
    }

    if (header.getComments() != null) {
      List<String> comments = Lists.newArrayList();
      for (String CO : header.getComments()) {
        if (CO != null) {
          comments.add(CO);
        }
      }
      samHeader.setComments(comments);
    }

    return samHeader;
  }  
}
