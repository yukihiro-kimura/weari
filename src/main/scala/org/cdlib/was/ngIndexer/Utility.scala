package org.cdlib.was.ngIndexer;

import java.io.{BufferedInputStream,File,InputStream,FileOutputStream,OutputStream};

import org.archive.io.{ArchiveReaderFactory,ArchiveRecord};

import scala.util.matching.Regex;

object Utility {
  /**
   *
   * Read a set of bytes from an input stream and pass them on to a function.
   *
   * @param stream The InputStream to return from.
   * @param f a function taking two arguments, an Int and Array[byte]. Used for side effects.
   * @return Unit
   */
  def readBytes (stream : InputStream, f : (Int, Array[Byte]) => Unit) : Unit = {
    var buffer = new Array[Byte](1024);
    var bytesRead = stream.read(buffer);
    while (bytesRead != -1)  {
      f (bytesRead, buffer);
      bytesRead = stream.read(buffer);
    }
  }

  /**
   *
   * Open an OutputStream on a File, ensuring that the stream is closed.
   *
   * @param file The File to open.
   * @param f a function taking two arguments, an Int and Array[byte]. Used for side effects.
   */
  def withFileOutputStream[A] (file : File, f : OutputStream => A) : A = {
    val out = new FileOutputStream(file);
    try {
      f (out);
    } finally {
      out.close();
    }
  }

  def readStreamIntoFile (file : File, in : InputStream) = {
    withFileOutputStream (file, (out) =>
      readBytes(in, (bytesRead, buffer) =>
        out.write(buffer, 0, bytesRead)));
  }

  def eachArc (arcFile : java.io.File) (f: (ArchiveRecordWrapper)=>Unit) {
    val reader = ArchiveReaderFactory.get(arcFile)
    val it = reader.iterator;
    while (it.hasNext) {
      val next = it.next;
      f (new ArchiveRecordWrapper(next, arcFile.getName));
      next.close;
    }
    reader.close;
  }

  def eachArc (stream : java.io.InputStream, arcName : String) (f: (ArchiveRecordWrapper)=>Unit) {
    val reader = ArchiveReaderFactory.get(arcName, stream, true);
    val it = reader.iterator;
    while (it.hasNext) {
      val next = it.next;
      f (new ArchiveRecordWrapper(next, arcName));
      next.close;
    }
    reader.close;
  }

}
