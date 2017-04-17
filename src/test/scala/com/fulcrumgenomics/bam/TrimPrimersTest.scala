/*
 * The MIT License
 *
 * Copyright (c) 2016 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.fulcrumgenomics.bam

import java.nio.file.Paths

import com.fulcrumgenomics.FgBioDef._
import com.fulcrumgenomics.testing.SamRecordSetBuilder.{Minus, Plus}
import com.fulcrumgenomics.testing.{SamRecordSetBuilder, UnitSpec}
import com.fulcrumgenomics.util.Io
import htsjdk.samtools.SAMFileHeader.SortOrder
import htsjdk.samtools.util.{CoordMath, SequenceUtil}
import htsjdk.samtools.{SAMRecord, CigarOperator => Op}

class TrimPrimersTest extends UnitSpec {
  val primerLines = Seq(
    Seq("chrom", "left_start", "left_end", "right_start", "right_end"),
    Seq("chr1", 100, 119, 200, 219),
    Seq("chr1", 200, 219, 300, 320),
    Seq("chr1", 300, 319, 400, 421),
    Seq("chr1", 400, 419, 500, 522)
  )

  val refFasta = Paths.get("src/test/resources/com/fulcrumgenomics/bam/trim_primers_test.fa")

  val maxPrimerLength = CoordMath.getLength(500, 522)
  val readLength = 50

  def primers: FilePath = {
    val tmp = makeTempFile("primers.", ".txt")
    Io.writeLines(tmp, primerLines.map(x => x.mkString("\t")))
    tmp
  }

  "TrimPrimers" should "trim reads that match primer locations" in {
    val builder = new SamRecordSetBuilder(readLength=readLength, sortOrder=SortOrder.coordinate)
    builder.addPair("q1", start1=100, start2=CoordMath.getStart(219, readLength))
    builder.addPair("q2", start1=200, start2=CoordMath.getStart(320, readLength))
    builder.addPair("q3", start1=300, start2=CoordMath.getStart(421, readLength))
    builder.addPair("q4", start1=400, start2=CoordMath.getStart(522, readLength))
    val bam = builder.toTempFile()
    val newBam = makeTempFile("trimmed.", ".bam")
    new TrimPrimers(input=bam, output=newBam, primers=primers, hardClip=false).execute()

    val reads = readBamRecs(newBam)
    reads should have size 8
    reads.foreach { rec =>
      if (rec.getReadNegativeStrandFlag) rec.getCigar.getLastCigarElement.getOperator shouldBe Op.SOFT_CLIP
      else                               rec.getCigar.getFirstCigarElement.getOperator shouldBe Op.SOFT_CLIP
    }
  }

  it should "trim reads that are off from primer locations by a little bit" in {
    val builder = new SamRecordSetBuilder(readLength=readLength, sortOrder=SortOrder.coordinate)
    builder.addPair("q1", start1=100+2, start2=CoordMath.getStart(219, readLength)-1)
    builder.addPair("q2", start1=200+1, start2=CoordMath.getStart(320, readLength)+2)
    builder.addPair("q3", start1=300+2, start2=CoordMath.getStart(421, readLength)+1)
    builder.addPair("q4", start1=400-1, start2=CoordMath.getStart(522, readLength)-2)
    val bam = builder.toTempFile()
    val newBam = makeTempFile("trimmed.", ".bam")
    new TrimPrimers(input=bam, output=newBam, primers=primers, hardClip=false).execute()

    val reads = readBamRecs(newBam)
    reads should have size 8
    reads.foreach { rec =>
      if (rec.getReadNegativeStrandFlag) rec.getCigar.getLastCigarElement.getOperator shouldBe Op.SOFT_CLIP
      else                               rec.getCigar.getFirstCigarElement.getOperator shouldBe Op.SOFT_CLIP
    }
  }

  it should "trim FR reads regardless of which is first/second of pair" in {
    val builder = new SamRecordSetBuilder(readLength=readLength, sortOrder=SortOrder.coordinate)
    builder.addPair("q1", start2=100, strand2=Plus, start1=CoordMath.getStart(219, readLength), strand1=Minus)
    builder.addPair("q2", start2=200, strand2=Plus, start1=CoordMath.getStart(320, readLength), strand1=Minus)
    builder.addPair("q3", start2=300, strand2=Plus, start1=CoordMath.getStart(421, readLength), strand1=Minus)
    builder.addPair("q4", start2=400, strand2=Plus, start1=CoordMath.getStart(522, readLength), strand1=Minus)
    val bam = builder.toTempFile()
    val newBam = makeTempFile("trimmed.", ".bam")
    new TrimPrimers(input=bam, output=newBam, primers=primers, hardClip=false).execute()

    val reads = readBamRecs(newBam)
    reads should have size 8
    reads.foreach { rec =>
      if (rec.getReadNegativeStrandFlag) rec.getCigar.getLastCigarElement.getOperator shouldBe Op.SOFT_CLIP
      else                               rec.getCigar.getFirstCigarElement.getOperator shouldBe Op.SOFT_CLIP
    }
  }

  it should "trim anything that's not an on-amplicon FR pair by the longest primer length" in {
    val builder = new SamRecordSetBuilder(readLength=readLength, sortOrder=SortOrder.coordinate)
    builder.addPair("q1", start1=100, start2=CoordMath.getStart(219, readLength)+20)            // Too far from primer sites
    builder.addPair("q2", start1=200, start2=CoordMath.getStart(320, readLength), strand2=Plus) // FF pair
    builder.addPair("q3", start1=300, start2=300, record2Unmapped=true)                         // Unmapped mate
    builder.addFrag("q4", start=CoordMath.getStart(219, readLength), strand=Minus)              // Fragment read
    val bam = builder.toTempFile()
    val newBam = makeTempFile("trimmed.", ".bam")
    new TrimPrimers(input=bam, output=newBam, primers=primers, hardClip=false).execute()

    val reads = readBamRecs(newBam)
    reads should have size 7
    reads.filter(r => !r.getReadUnmappedFlag).foreach { rec =>
      val elem = if (rec.getReadNegativeStrandFlag) rec.getCigar.getLastCigarElement else rec.getCigar.getFirstCigarElement
      elem.getOperator shouldBe Op.SOFT_CLIP
      elem.getLength   shouldBe maxPrimerLength
    }
  }

  it should "trim back reads that are more than fully overlapped after clipping" in {
    val builder = new SamRecordSetBuilder(readLength=120, sortOrder=SortOrder.coordinate)
    builder.addPair("q1", start1=100, start2=100)
    val bam = builder.toTempFile()
    val newBam = makeTempFile("trimmed.", ".bam")
    new TrimPrimers(input=bam, output=newBam, primers=primers, hardClip=false).execute()

    val reads = readBamRecs(newBam)
    reads should have size 2
    reads.foreach ( rec => rec.getCigarString shouldBe "20S80M20S" )
  }

  it should "recalculate NM/UQ/MD if a reference is given" in {
    import SortOrder._
    val zeroErrors   = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    val oneErrors    = "AAAAAGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    val twoErrors    = "AAAAAGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGAAAAA"
    val threeErrors  = "AAAAAGAAAAAAAAAAAAAAAAAAGAAAAAAAAAAAAAAAAAAAGAAAAA"
    def rc(s: String) = SequenceUtil.reverseComplement(s)
    def isRev(rec: SAMRecord) = rec.getReadNegativeStrandFlag

    for (inOrder <- Seq(queryname, coordinate, unsorted); outOrder <- Seq(queryname, coordinate, unsorted)) {
      val builder = new SamRecordSetBuilder(readLength=readLength, sortOrder=inOrder)
      builder.addPair("q1", start1=100, start2=CoordMath.getStart(219, readLength)).foreach(r => r.setReadString(if (isRev(r)) zeroErrors.reverse  else zeroErrors))
      builder.addPair("q2", start1=200, start2=CoordMath.getStart(320, readLength)).foreach(r => r.setReadString(if (isRev(r)) oneErrors.reverse   else oneErrors))
      builder.addPair("q3", start1=300, start2=CoordMath.getStart(421, readLength)).foreach(r => r.setReadString(if (isRev(r)) twoErrors.reverse   else twoErrors))
      builder.addPair("q4", start1=400, start2=CoordMath.getStart(522, readLength)).foreach(r => r.setReadString(if (isRev(r)) threeErrors.reverse else threeErrors))

      val bam = builder.toTempFile()
      val newBam = makeTempFile("trimmed.", ".bam")
      new TrimPrimers(input=bam, output=newBam, primers=primers, hardClip=false, ref=Some(refFasta), sortOrder=Some(outOrder)).execute()

      val reads = readBamRecs(newBam)
      reads should have size 8
      reads.foreach { rec =>
        rec.getAttribute("NM") should not be null
        rec.getAttribute("MD") should not be null
        rec.getAttribute("UQ") should not be null

        val expectedNm = (rec.getReadNegativeStrandFlag, rec.getReadName) match {
          case (false, "q1") => 0
          case (true , "q1") => 0
          case (false, "q2") => 0
          case (true , "q2") => 0
          case (false, "q3") => 1
          case (true , "q3") => 1
          case (false, "q4") => 2
          case (true , "q4") => 2
          case (_,        _) => unreachable()
        }

        rec.getIntegerAttribute("NM") shouldBe expectedNm
      }
    }
  }
}