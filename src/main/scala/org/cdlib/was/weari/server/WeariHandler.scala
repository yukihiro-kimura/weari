package org.cdlib.was.weari.server;

import org.cdlib.was.weari._;
import org.cdlib.was.weari.thrift;

import java.io.InputStream;
import java.util.{ List => JList, Map => JMap };
import java.util.zip.GZIPInputStream;

import com.codahale.jerkson.Json.parse;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.{ FileSystem, FSDataInputStream, FSDataOutputStream, Path };

import org.apache.solr.client.solrj.impl.StreamingUpdateSolrServer;

import scala.collection.JavaConversions.{ collectionAsScalaIterable, mapAsScalaMap };

import grizzled.slf4j.Logging;

class WeariHandler(config: Config)
  extends thrift.Server.Iface with Logging {

  val conf = new Configuration();
  val fs = FileSystem.get(conf);

  /**
   * Get the HDFS path for the JSON parse of an ARC file.
   * Can return either a .gz or a non-gzipped file.
   * 
   * @param arc The name of the ARC file.
   * @return Some[Path] or None. 
   */
  def getPath(arc: String): Option[Path] = {
    val json = "%s/%s.json".format(config.jsonBaseDir(), arc);
    val jsongz = "%s.gz".format(json);
    val jsonPath = new Path(json);
    if (fs.exists(jsonPath)) {
      return Some(jsonPath);
    } else {
      val jsongzPath = new Path(jsongz);
      if (fs.exists(jsongzPath)) {
        return Some(jsongzPath);
      }
    }
    return None;
  }

  /**
   * Index a set of ARCs on a solr server.
   *
   * @param solr The URI of the solr server to index on.
   * @param filterQuery A solr query string to return candidates for
   *   documents to be possibly merged with.
   * @param arcs A list of ARC names to index
   * @param extraId String to append to solr document IDs.
   * @param extraFields Map of extra fields to append to solr documents.
   */
  def index(solr : String,
            filterQuery : String,
            arcs : JList[String],
            extraId : String,
            extraFields : JMap[String, String]) {
    val server = new StreamingUpdateSolrServer(solr,
      config.queueSize(),
      config.threadCount());
    val filter = new QuickIdFilter(filterQuery, server);
    val indexer = new SolrIndexer(server = server,
                                  filter = filter,
                                  extraId = extraId,
                                  extraFields = extraFields.toMap);
    val arcPaths = arcs.toSeq.flatMap(getPath(_));
    if (arcPaths.size != arcs.size) {
      /* missing JSON files for some arcs */
      throw new thrift.IndexException("Some ARCs not yet parsed.");
    } else {
      for ((path, arcname) <- arcPaths.zip(arcs)) {
        var in : InputStream = null;
        try {
          in = fs.open(path);
          if (path.getName.endsWith("gz")) {
            in = new GZIPInputStream(in);
          }
          indexer.index(parse[List[ParsedArchiveRecord]](in));
        } catch {
          case ex : Exception => {
            error("Error while indexing %s: %s".format(arcname, ex), ex);
            if (in != null) in.close;
          }
        }
      }
    }
  }

  def ping {
    println("pinged");
  }
}
