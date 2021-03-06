package com.inmobi.databus.readers;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.mapred.TextInputFormat;

import com.inmobi.databus.files.CollectorFile;
import com.inmobi.databus.files.DatabusStreamFile;
import com.inmobi.databus.files.FileMap;
import com.inmobi.databus.partition.PartitionCheckpoint;
import com.inmobi.databus.partition.PartitionId;
import com.inmobi.messaging.metrics.CollectorReaderStatsExposer;

public class LocalStreamCollectorReader extends 
    DatabusStreamReader<DatabusStreamFile> {

  protected final String streamName;

  private static final Log LOG = LogFactory.getLog(
      LocalStreamCollectorReader.class);

  private final String collector;
  
  public LocalStreamCollectorReader(PartitionId partitionId, 
      FileSystem fs, String streamName, Path streamDir, Configuration conf,
      long waitTimeForFileCreate, CollectorReaderStatsExposer metrics)
          throws IOException {
    super(partitionId, fs, streamDir,
        TextInputFormat.class.getCanonicalName(), conf, waitTimeForFileCreate,
        metrics, false);
    this.streamName = streamName;
    this.collector = partitionId.getCollector();
  }

  protected void buildListing(FileMap<DatabusStreamFile> fmap, PathFilter pathFilter)
      throws IOException {
    Calendar current = Calendar.getInstance();
    Date now = current.getTime();
    current.setTime(buildTimestamp);
    while (current.getTime().before(now)) {
      Path hhDir =  getHourDirPath(streamDir, current.getTime());
      int hour = current.get(Calendar.HOUR_OF_DAY);
      if (fs.exists(hhDir)) {
        while (current.getTime().before(now) && 
            hour  == current.get(Calendar.HOUR_OF_DAY)) {
          Path dir = getMinuteDirPath(streamDir, current.getTime());
          // Move the current minute to next minute
          current.add(Calendar.MINUTE, 1);
          doRecursiveListing(dir, pathFilter, fmap);
        } 
      } else {
        // go to next hour
        LOG.info("Hour directory " + hhDir + " does not exist");
        current.add(Calendar.HOUR_OF_DAY, 1);
        current.set(Calendar.MINUTE, 0);
      }
    }
  }

  @Override
  protected DatabusStreamFile getStreamFile(Date timestamp) {
    return getDatabusStreamFile(streamName, timestamp);
  }

  protected DatabusStreamFile getStreamFile(FileStatus status) {
    return DatabusStreamFile.create(streamName, status.getPath().getName());
  }

  public FileMap<DatabusStreamFile> createFileMap() throws IOException {
    return new FileMap<DatabusStreamFile>() {
    @Override
    protected void buildList() throws IOException {
      buildListing(this, pathFilter);
    }
    
    @Override
    protected TreeMap<DatabusStreamFile, FileStatus> createFilesMap() {
      return new TreeMap<DatabusStreamFile, FileStatus>();
    }

    @Override
    protected DatabusStreamFile getStreamFile(String fileName) {
      return DatabusStreamFile.create(streamName, fileName);
    }

    @Override
    protected DatabusStreamFile getStreamFile(FileStatus file) {
      return DatabusStreamFile.create(streamName, file.getPath().getName());
    }

    @Override
    protected PathFilter createPathFilter() {
      return new PathFilter() {
        @Override
        public boolean accept(Path p) {
          if (p.getName().startsWith(collector)) {
            return true;
          }
          return false;
        }          
      };
    }
    };
  }
  
  public byte[] readLine() throws IOException {
    byte[] line = readNextLine();
    while (line == null) { // reached end of file
      if (closed) {
        LOG.info("Stream closed");
        break;
      }
      LOG.info("Read " + getCurrentFile() + " with lines:" + currentLineNum);
      if (!nextFile()) { // reached end of file list
        LOG.info("could not find next file. Rebuilding");
        build(getDateFromDatabusStreamFile(streamName,
        getCurrentFile().getName()));
        if (!setIterator()) {
          LOG.info("Could not find current file in the stream");
          // set current file to next higher entry
          if (!setNextHigherAndOpen(currentFile)) {
            LOG.info("Could not find next higher entry for current file");
            return null;
          } else {
            // read line from next higher file
            LOG.info("Reading from " + getCurrentFile() + ". The next higher file" +
                " after rebuild");
          }
        } else if (!nextFile()) { // reached end of stream
          LOG.info("Reached end of stream");
          return null;
        } else {
          LOG.info("Reading from " + getCurrentFile() + " after rebuild");
        }
      } else {
        // read line from next file
        LOG.info("Reading from next file " + getCurrentFile());
      }
      line = readNextLine();
    }
    return line;
  }

  public static Date getBuildTimestamp(String streamName, String collectorName,
      PartitionCheckpoint partitionCheckpoint) {
    String fileName = null;
    try {
      if (partitionCheckpoint != null) {
        fileName = partitionCheckpoint.getFileName();
        if (fileName != null && 
            !isDatabusStreamFile(streamName, fileName)) {
          fileName = getDatabusStreamFileName(collectorName, fileName);
        }
      }
      return getDateFromStreamFile(streamName, fileName);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid fileName:" + 
          fileName, e);
    }
  }

  static Date getDateFromDatabusStreamFile(String streamName, String fileName) {
    return DatabusStreamFile.create(streamName, fileName).getCollectorFile()
        .getTimestamp();
  }

  static Date getDateFromStreamFile(String streamName,
      String fileName) throws Exception {
    return getDatabusStreamFileFromLocalStreamFile(streamName, fileName).
        getCollectorFile().getTimestamp();
  }

  public static String getDatabusStreamFileName(String streamName,
      Date date) {
    return getDatabusStreamFile(streamName, date).toString();  
  }

  public static DatabusStreamFile getDatabusStreamFile(String streamName,
      Date date) {
    return new DatabusStreamFile("", new CollectorFile(streamName, date, 0),
        "gz");  
  }

  public static DatabusStreamFile getDatabusStreamFileFromLocalStreamFile(
      String streamName,
      String localStreamfileName) {
    return DatabusStreamFile.create(streamName, localStreamfileName);  
  }

  static boolean isDatabusStreamFile(String streamName, String fileName) {
    try {
      getDatabusStreamFileFromLocalStreamFile(streamName, fileName);
    } catch (IllegalArgumentException ie) {
      return false;
    }
    return true;
  }

  public static String getDatabusStreamFileName(String collector,
      String collectorFile) {
    return getDatabusStreamFile(collector, collectorFile).toString();  
  }

  public static DatabusStreamFile getDatabusStreamFile(String collector,
      String collectorFileName) {
    return new DatabusStreamFile(collector,
        CollectorFile.create(collectorFileName), "gz");  
  }

}
