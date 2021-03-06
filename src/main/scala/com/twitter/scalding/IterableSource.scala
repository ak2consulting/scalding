/*
Copyright 2012 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.scalding

import com.twitter.meatlocker.tap.MemorySourceTap

import cascading.flow.FlowProcess
import cascading.scheme.local.{TextDelimited => CLTextDelimited}
import cascading.scheme.Scheme
import cascading.tuple.Tuple
import cascading.tuple.Fields

import scala.collection.mutable.Buffer
import scala.collection.JavaConverters._

/**
 * Allows working with an iterable object defined in the job (on the submitter)
 * to be used within a Job as you would a Pipe/RichPipe
 *
 * These lists should probably be very tiny by Hadoop standards.  If they are
 * getting large, you should probably dump them to HDFS and use the normal
 * mechanisms to address the data (a FileSource).
 */
case class IterableSource[T](@transient iter: Iterable[T], inFields : Fields = Fields.NONE)
  (implicit set: TupleSetter[T]) extends Source with Mappable[T] {

  def fields = {
    if (inFields.isNone && set.arity > 0) {
      RichPipe.intFields(0 until set.arity)
    }
    else inFields
  }

  @transient
  private val asBuffer : Buffer[Tuple] = iter.map { set(_) }.toBuffer

  override def localScheme : LocalScheme = {
    // This is a hack because the MemoryTap doesn't actually care what the scheme is
    // it just holds the fields
    // TODO implement a proper Scheme for MemoryTap
    new CLTextDelimited(fields, "\t", null : Array[Class[_]])
  }

  override def hdfsScheme : Scheme[_ <: FlowProcess[_],_,_,_,_,_] = {
    hdfsTap.getScheme.asInstanceOf[Scheme[_ <: FlowProcess[_],_,_,_,_,_]]
  }

  private lazy val hdfsTap = new MemorySourceTap(asBuffer.asJava, fields)

  override def createTap(readOrWrite : AccessMode)(implicit mode : Mode) : RawTap = {
    if (readOrWrite == Write) {
      error("IterableSource is a Read-only Source")
    }
    mode match {
      case Local(_) => new MemoryTap(localScheme, asBuffer)
      case Test(_) => new MemoryTap(localScheme, asBuffer)
      case Hdfs(_, _) => hdfsTap.asInstanceOf[RawTap]
      case HadoopTest(_,_) => hdfsTap.asInstanceOf[RawTap]
      case _ => error("Unsupported mode for IterableSource: " + mode.toString)
    }
  }
}
