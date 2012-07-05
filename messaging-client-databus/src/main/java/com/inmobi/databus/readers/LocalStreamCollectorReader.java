package com.inmobi.databus.readers;

import java.io.IOException;
import java.util.Date;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.mapred.TextInputFormat;

import com.inmobi.databus.files.DatabusStreamFile;
import com.inmobi.databus.files.FileMap;
import com.inmobi.databus.partition.PartitionCheckpoint;
import com.inmobi.databus.partition.PartitionId;

public class LocalStreamCollectorReader extends DatabusStreamReader {

  private static final Log LOG = LogFactory.getLog(
      LocalStreamCollectorReader.class);

  private final String collector;
  
  public LocalStreamCollectorReader(PartitionId partitionId, 
      FileSystem fs, String streamName, Path streamDir, Configuration conf)
          throws IOException {
    super(partitionId, fs, streamName, streamDir,
        TextInputFormat.class.getCanonicalName(), conf, false);
    this.collector = partitionId.getCollector();
  }
  
  public FileMap<DatabusStreamFile> createFileMap() throws IOException {
    return new StreamFileMap() {
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
  
  public String readLine() throws IOException {
    String line = readNextLine();
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
    if (partitionCheckpoint != null) {
      fileName = partitionCheckpoint.getFileName();
      if (fileName != null && 
          !isDatabusStreamFile(streamName, fileName)) {
        fileName = getDatabusStreamFileName(collectorName, fileName);
      }
    }
    return getBuildTimestamp(streamName, fileName);
  }
}
