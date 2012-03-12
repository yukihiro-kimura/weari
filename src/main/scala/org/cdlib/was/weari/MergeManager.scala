/* Copyright (c) 2011-2012 The Regents of the University of California */

package org.cdlib.was.weari;

import java.util.{Collection=>JCollection}

import org.apache.solr.client.solrj.{SolrQuery,SolrServer};
import org.apache.solr.common.{SolrDocument,SolrInputDocument};
import org.apache.solr.client.solrj.util.ClientUtils.toSolrInputDocument;

import org.archive.util.BloomFilter64bit;

import org.cdlib.was.weari.SolrFields.{ID_FIELD, MULTI_VALUED_FIELDS, SINGLE_VALUED_FIELDS};

import org.cdlib.was.weari.Utility.{null2option,null2seq};

import scala.collection.mutable.{Map,SynchronizedMap,HashMap};

/**
 * Class used to keep track of merging.
 * We need to merge documents with ones that are already in the index.
 * This class provides a merge function to do this.
 * We also need to sometimes merge two documents with the same id
 * before we can commit to the indexer; for instance when two docs in the same
 * crawl have the same id.
 * So we also need to keep track of documents that we have previously merged, in memory.
 *
 * @author Erik Hetzner <erik.hetzner@ucop.edu>
 */
class MergeManager (candidatesQuery : String, server : SolrServer, n : Int) {
  def this (candidatesQuery : String, server : SolrServer) = 
    this(candidatesQuery, server, 100000);

  /* candiatesQuery is a query that should return all <em>possible</em> merge
   * candidates. It can return everything, though this would not be
   * efficient */

  val bf = new BloomFilter64bit(n, 12);

  /* keeps track of what has been merged, or checked for merge, so far */
  var tracked : Map[String,SolrInputDocument] = 
    new HashMap[String,SolrInputDocument] 
    with SynchronizedMap[String,SolrInputDocument];

  /* initialize */
  if (server != null) {
    val newq = new SolrQuery(candidatesQuery).setParam("fl", ID_FIELD).setRows(1000);
    val docs = new solr.SolrDocumentCollection(server, newq);
    for (doc <- docs) bf.add(getId(doc))
  }

  /**
   * Get a single document by its id. Return None if no document 
   * has that id.
   * 
   */
  def getDocById(id : String) : Option[SolrInputDocument] = {
    tracked.get(id) match {
      case Some(doc) => Some(doc);
      case None => {
        /* otherwise try to get it from the solr server */
        val q = (new SolrQuery).setQuery("id:\"%s\"".format(id));
        val docs = new solr.SolrDocumentCollection(server, q);
        return docs.headOption.map(toSolrInputDocument(_));
      }
    }
  }

  /* Returns true if this needs a merge */
  private def needsMerge (id : String) : Boolean = 
    (bf.contains(id) && getDocById(id).isDefined);

  /**
   * Merge a document with existing docs with the same id.
   */
  def merge(doc : SolrInputDocument) : SolrInputDocument = {
    val id = getId(doc);
    var retval = doc;
    if (this.needsMerge(id)) {
      retval = getDocById(id).map(mergeDocs(_, doc)).getOrElse(doc);
    }
    /* Whether or not we merged this, we save it for possible later merging */
    tracked.put(id, retval);
    bf.add(id);
    return retval;
  }

  /**
   * Return the ID field in a solr document.
   */
  private def getId (doc : SolrInputDocument) : String = 
    doc.getFieldValue(ID_FIELD).asInstanceOf[String];

  /**
   * Return the ID field in a solr document.
   */
  private def getId (doc : SolrDocument) : String = 
    doc.getFieldValue(ID_FIELD).asInstanceOf[String];

  /**
   * Merge two documents into one, presuming they have the same id.
   * Multi-value fields are appended.
   */
  private def mergeDocs (a : SolrInputDocument, b : SolrInputDocument) : SolrInputDocument = {
    val retval = new SolrInputDocument;
    if (a.getFieldValue(ID_FIELD) != b.getFieldValue(ID_FIELD)) {
      throw new Exception;
    } else {
      /* identical fields */
      for (fieldName <- SINGLE_VALUED_FIELDS) {
        val content = {
          val aval = a.getFieldValue(fieldName);
          if (aval != null && aval != "")
            aval;
          else b.getFieldValue(fieldName);
        }
        null2option(content).map(retval.setField(fieldName, _));
      }
      /* fields to merge */
      for (fieldName <- MULTI_VALUED_FIELDS) {
        val values = (null2seq(a.getFieldValues(fieldName)) ++
                      null2seq(b.getFieldValues(fieldName))).distinct;
        for (value <- values)
          retval.addField(fieldName, value);
      }
    }
    return retval;
  }
  
  def loadDocs (q : String) {
    val newq = new SolrQuery(q).setRows(1000);
    val docs = new solr.SolrDocumentCollection(server, newq);
    for (doc <- docs) {
      val id = getId(doc);
      tracked.put(id, toSolrInputDocument(doc));
      bf.add(id);
    }
  }
}
