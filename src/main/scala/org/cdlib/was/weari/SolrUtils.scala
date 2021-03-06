/*
Copyright (c) 2009-2012, The Regents of the University of California

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

* Neither the name of the University of California nor the names of
its contributors may be used to endorse or promote products derived
from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.cdlib.was.weari;

import org.apache.solr.common.{ SolrDocument, SolrInputDocument, SolrInputField };
import org.apache.solr.client.solrj.util.ClientUtils;

import org.cdlib.was.weari.SolrFields.{ getId, COPY_FIELDS, ID_FIELD, MULTI_VALUED_MERGE_FIELDS };
import org.cdlib.was.weari.Utility.null2option;

import scala.collection.JavaConversions.collectionAsScalaIterable;

object SolrUtils {
  /**
   * Convert a ParsedArchiveRecord into a SolrInputDocument, merging
   * in extraFields and extraId.
   */
  def record2inputDocument (record : ParsedArchiveRecord, 
                            extraFields : Map[String, Any], 
                            extraId : String) : SolrInputDocument = {
    val doc = ParsedArchiveRecordSolrizer.convert(record);
    addFields(doc, extraFields.toSeq : _*);
    doc.setField(ID_FIELD, "%s.%s".format(getId(doc), extraId));
    return doc;
  }

  /**
   * Add a set of fields to a SolrInputDocument.
   */
  def addFields(doc : SolrInputDocument, fields : Pair[String, Any]*) {
    for (field <- fields) {
      addField(doc, field._1, field._2);
    }
  }

  /**
   * Add a field to a SolrInputDocument.
   * If field value is None or null, do not add.
   * If field value is Some(x), add x.
   * If field value is Traversable[Any], add each value.
   * Otherwise just add it.
   */
  def addField(doc : SolrInputDocument, name : String, value : Any) {
    value match {
      case null | None    => ();
      case Some(s)        => doc.addField(name, s);
      case seq : Traversable[Any] =>
        for (v <- seq) doc.addField(name, v);
      case s              => doc.addField(name, s);
    }
  }

  /**
   * Make a new SolrInputDocument with the fields provided.
   * See documentation on addFields for field processing
   */
  def makeDoc(fields : Pair[String, Any]*) : SolrInputDocument = {
    var doc = new SolrInputDocument;
    addFields(doc, fields : _*);
    return doc;
  }

  def mkInputField (name : String, value : Any) : SolrInputField = {
    val retval = new SolrInputField(name);
    retval.addValue(value, 1.0f);
    return retval;
  }

  def mkInputField (name : String, values : Seq[Any]) : SolrInputField = {
    val retval = new SolrInputField(name);
    for (value <- values)
      retval.addValue(value, 1.0f);
    return retval;
  }

  /**
   * Convert a SolrDocument to a SolrInputDocument, stripping out copy fields along the way.
   */
  def toSolrInputDocument (doc : SolrDocument) : SolrInputDocument = {
    val retval = ClientUtils.toSolrInputDocument(doc);
    for (fieldname <- COPY_FIELDS) 
      retval.removeField(fieldname);
    return retval;
  }
}
