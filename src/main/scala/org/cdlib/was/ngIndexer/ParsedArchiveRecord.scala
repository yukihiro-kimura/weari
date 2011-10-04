/* Copyright (c) 2011 The Regents of the University of California */

package org.cdlib.was.ngIndexer;

import java.util.Date;
import net.liftweb.json.{DefaultFormats,JsonParser,NoTypeHints,Serialization}
import net.liftweb.json.JsonAST.JValue;

import org.archive.net.UURIFactory;
import java.io.{File,InputStreamReader,Writer};

import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.client.solrj.util.ClientUtils;

import org.cdlib.was.ngIndexer.SolrFields._;
import org.cdlib.was.ngIndexer.SolrDocumentModifier.{shouldIndexContentType,updateDocBoost,updateDocUrls,updateContentType,updateFields};

/**
 * A class representing a WASArchiveRecord that has been parsed.
 */
case class ParsedArchiveRecord (
  /* being a case class makes this easy to serialize as JSON */
  val filename : String,
  val digest : String,
  val url : String,
  val date : Date,
  val title : String,
  val length : Long,
  val content : Option[String],
  val suppliedContentType : ContentType,
  val detectedContentType : Option[ContentType],
  val outlinks : Option[Seq[Long]]) extends WASArchiveRecord {

  def getFilename = filename;
  def getDigestStr = Some(digest);
  def getUrl = url;
  def getDate = date;
  def getLength = length;
  def getStatusCode = 200;
  def isHttpResponse = true;
  def getContentType = suppliedContentType;

  def toDocument : SolrInputDocument = {
    val doc = new SolrInputDocument;
    /* set the fields */
    val uuri = UURIFactory.getInstance(url);
    updateFields(doc,
                 ARCNAME_FIELD        -> filename,
                 ID_FIELD             -> "%s.%s".format(uuri.toString, digest),
                 DIGEST_FIELD         -> digest,
                 DATE_FIELD           -> date,
                 TITLE_FIELD          -> title,
                 CONTENT_LENGTH_FIELD -> length,
                 CONTENT_FIELD        -> content);
    updateDocBoost(doc, 1.0f);
    updateDocUrls(doc, url);
    updateContentType(doc, detectedContentType, suppliedContentType);
    return doc;
  }
}

object ParsedArchiveRecord {
  def apply(rec : WASArchiveRecord,
            parseResult : Option[MyParseResult]) : ParsedArchiveRecord = {
    val suppliedContentType = rec.getContentType;
    val detectedContentType = parseResult.map(_.contentType)
    new ParsedArchiveRecord(filename = rec.getFilename,
                            digest = rec.getDigestStr.getOrElse("-"),
                            url = rec.getUrl,
                            date = rec.getDate,
                            title = parseResult.flatMap(_.title).getOrElse(""),
                            length = rec.getLength,
                            content = if (shouldIndexContentType(suppliedContentType) ||
                                          shouldIndexContentType(detectedContentType.getOrElse(ContentType.DEFAULT))) {
                              parseResult.flatMap(_.content);
                            } else { 
                              None;
                            },
                            suppliedContentType = suppliedContentType,
                            detectedContentType = detectedContentType,
                            outlinks = parseResult.map(_.outlinks));
  }
}
