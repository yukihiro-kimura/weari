package org.cdlib.was.ngIndexer;

import java.io.File;
import java.util.regex.Pattern;
import org.archive.net.UURIFactory;
import org.archive.io.ArchiveRecord;
import org.apache.tika.metadata.Metadata;
import org.apache.solr.common.{SolrDocument,SolrInputDocument};
import org.archive.io.arc.ARCRecord;
import org.apache.tika.parser.{AutoDetectParser,ParseContext,Parser};
import org.apache.tika.metadata.HttpHeaders;
import org.xml.sax.ContentHandler;
import org.apache.solr.client.solrj.SolrQuery;
import org.cdlib.was.ngIndexer.webgraph.WebGraphContentHandler;
import org.cdlib.was.ngIndexer.SolrIndexer.{ARCNAME_FIELD,
                                            BOOST_FIELD,
                                            CANONICALURL_FIELD,
                                            CONTENT_FIELD,
                                            CONTENT_LENGTH_FIELD,
                                            DATE_FIELD,
                                            DIGEST_FIELD,
                                            HOST_FIELD,
                                            ID_FIELD,
                                            JOB_FIELD,
                                            PROJECT_FIELD,
                                            SITE_FIELD,
                                            SPECIFICATION_FIELD,
                                            TITLE_FIELD,
                                            TSTAMP_FIELD,
                                            TYPE_FIELD,
                                            URL_FIELD,
                                            URLFP_FIELD };
import scala.collection.JavaConversions.asScalaIterable;

/** Class for processing (W)ARC files into Solr documents.
  *
  * @author egh
  */
class SolrProcessor {
  val parser : Parser = new AutoDetectParser();
  /* date formatter for solr */
  val dateFormatter = new java.text.SimpleDateFormat("yyyyMMddHHmmssSSS");
  /* regular expression to match against mime types which should have
     outlinks indexed */
  val webGraphTypeRE = Pattern.compile("^(.*html.*|application/pdf)$");

  /** Update the boost in a document.
    */
  def updateDocBoost (doc : SolrInputDocument,
                      boost : Float) {
    doc.setDocumentBoost(boost);
    doc.addField(BOOST_FIELD, boost);
  }
    
  /** Update the url & digest fields in a document.
    */
  def updateDocUrlDigest (doc : SolrInputDocument, 
                          url : String,
                          digest : String) {
    
    val uuri = UURIFactory.getInstance(url);
    val host = uuri.getHost;
    doc.addField(ID_FIELD, "%s.%s".format(uuri.toString, digest));
    doc.addField(DIGEST_FIELD, digest);
    doc.addField(HOST_FIELD, host);
    doc.addField(SITE_FIELD, host);
    doc.addField(URL_FIELD, url, 1.0f);
    doc.addField(TSTAMP_FIELD, dateFormatter.format(new java.util.Date(System.currentTimeMillis())), 1.0f);
    doc.addField(URLFP_FIELD, UriUtils.fingerprint(uuri));
    doc.addField(CANONICALURL_FIELD, uuri.toString, 1.0f);
  }

  /** Turn an existing SolrDocument into a SolrInputDocument suitable
    * for sending back to solr.
    */
  def doc2InputDoc (doc : SolrDocument) : SolrInputDocument = {
    val idoc = new SolrInputDocument();
    for (fieldName <- SolrIndexer.SINGLE_VALUED_FIELDS) {
      idoc.addField(fieldName, doc.getFirstValue(fieldName));
    }
    for (fieldName <- SolrIndexer.MULTI_VALUED_FIELDS) {
      for (value <- doc.getFieldValues(fieldName)) {
        idoc.addField(fieldName, value);
      }
    }    
    return idoc;
  }

  val MIN_BOOST = 0.1f;
  val MAX_BOOST = 10.0f;

  /** Take an archive record & return a solr document.
    *
    */
  def record2doc(archiveRecord : ArchiveRecord) : Option[Pair[String,SolrInputDocument]] = {
    archiveRecord match {
      case rec : ARCRecord => {
        Utility.skipHttpHeader(rec);
        val tikaMetadata = new Metadata();
        val parseContext = new ParseContext();
        val url = rec.getMetaData.getUrl;
        val recHeader = rec.getHeader;
        val contentType = rec.getMetaData.getMimetype.toLowerCase;
        val doc = new SolrInputDocument();
        val indexContentHandler = new NgIndexerContentHandler(rec.getHeader.getLength  >= 1048576);
        val wgContentHandler = new WebGraphContentHandler(url, rec.getHeader.getDate);
        val contentHandler = new MultiContentHander(List[ContentHandler](wgContentHandler, indexContentHandler));

        tikaMetadata.set(HttpHeaders.CONTENT_LOCATION, url);
        tikaMetadata.set(HttpHeaders.CONTENT_TYPE, contentType);
        try {
          try {
            parser.parse(rec, contentHandler, tikaMetadata, parseContext);
          } catch {
            case ex : Throwable => {
              System.err.println(String.format("Error reading %s", rec.getHeader.getUrl));
              ex.printStackTrace(System.err);
            }
          }
          /* finish index */
          rec.close;
          indexContentHandler.contentString.map(str=>doc.addField(CONTENT_FIELD, str));

          val title = tikaMetadata.get("title") match {
            case s : String => s
            case null => ""
          }
          val digest = archiveRecord match {
            case rec : ARCRecord => rec.getDigestStr;
            case _               => ""
          }
          updateDocBoost(doc, 1.0f);
          updateDocUrlDigest(doc, url, digest);
          doc.addField(DATE_FIELD, recHeader.getDate.toLowerCase, 1.0f);
          doc.addField(TYPE_FIELD, tikaMetadata.get(HttpHeaders.CONTENT_TYPE), 1.0f);
          doc.addField(TITLE_FIELD, title, 1.0f);
          doc.addField(CONTENT_LENGTH_FIELD, recHeader.getLength, 1.0f);
          /* finish webgraph */
          if (webGraphTypeRE.matcher(tikaMetadata.get(HttpHeaders.CONTENT_TYPE)).matches) {
            val outlinks = wgContentHandler.outlinks;
            if (outlinks.size > 0) {
              val outlinkFps = for (l <- outlinks) 
                               yield UriUtils.fingerprint(l.to);
              for (fp <- outlinkFps.toList.distinct.sortWith((a,b)=>(a < b))) {
                doc.addField("outlinks", fp);
              }
            }
          }
          return Some((url, doc));
      } catch {
          case ex : Exception => ex.printStackTrace(System.err);
          return None;
        }
      }
    }
  }

  /** For each record in a file, call the function.
    */
  def processFile (file : File) (func : (String,SolrInputDocument) => Unit) {
    if (file.isDirectory) {
      for (c <- file.listFiles) {
        processFile(c)(func);
      }
    } else if (file.getName.indexOf("arc.gz") != -1) {
      Utility.eachArc(file, (rec)=>record2doc(rec).map((p)=>func(p._1, p._2)));
    }
  }

   def mergeDocs (a : SolrInputDocument, b : SolrInputDocument) : SolrInputDocument = {
    val retval = new SolrInputDocument;
    if (a.getFieldValue(ID_FIELD) != b.getFieldValue(ID_FIELD)) {
      throw new Exception;
    } else {
      /* identical fields */
      for (fieldName <- SolrIndexer.SINGLE_VALUED_FIELDS) {
        retval.setField(fieldName, a.getFieldValue(fieldName));
      }
      /* fields to merge */
      for (fieldName <- SolrIndexer.MULTI_VALUED_FIELDS) {
        val valuesA = a.getFieldValues(fieldName) match {
          case null => List();
          case x => x.toList;
        }
        val valuesB = b.getFieldValues(fieldName) match {
          case null => List();
          case x => x.toList;
        }
        val values = (valuesA ++ valuesB).distinct;
        for (value <- values) {
          retval.addField(fieldName, value);
        }
      }
    }
    return retval;
  }

  // def updateBoosts (g : RankedWebGraph) = {
  //   var fp2boost = new scala.collection.mutable.HashMap[Long, Float]();
  //   val it = g.nodeIterator;
  //   while (it.hasNext) {
  //     it.next;
  //     fp2boost.update(UriUtils.fingerprint(it.url), it.boost);
  //   }
  //   def updateBoost (doc : SolrDocument) : SolrInputDocument = {
  //     val idoc = doc2InputDoc(doc);
  //     val urlfp = doc.getFirstValue(SolrIndexer.URLFP_FIELD).asInstanceOf[Long];
  //     val boost1 = fp2boost.get(urlfp).getOrElse(doc.getFirstValue("boost").asInstanceOf[Float]);
  //     val boost = Math.min(MAX_BOOST, Math.max(MIN_BOOST, boost1));
  //     if (boost > 11.0f) throw new RuntimeException();
  //     idoc.setDocumentBoost(boost);
  //     idoc.removeField(SolrIndexer.BOOST_FIELD);
  //     idoc.setField(SolrIndexer.BOOST_FIELD, boost);
  //     idoc;
  //   }
  //   val q = new SolrQuery().setQuery("*:*").setRows(500);
  //   updateDocs(q, updateBoost);
  // }
}
