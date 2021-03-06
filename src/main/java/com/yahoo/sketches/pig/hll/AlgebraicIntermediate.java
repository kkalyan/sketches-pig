/*
 * Copyright 2017, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.pig.hll;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.pig.EvalFunc;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;

import com.yahoo.memory.Memory;
import com.yahoo.sketches.hll.HllSketch;
import com.yahoo.sketches.hll.TgtHllType;
import com.yahoo.sketches.hll.Union;

/**
 * Class used to calculate the intermediate combiner pass of an <i>Algebraic</i> sketch
 * operation. This is called from the combiner, and may be called multiple times (from a mapper
 * and from a reducer). It will receive a bag of values returned by either the <i>Intermediate</i>
 * stage or the <i>Initial</i> stages, so it needs to be able to differentiate between and
 * interpret both types.
 * 
 * @author Alexander Saydakov
 */
abstract class AlgebraicIntermediate extends EvalFunc<Tuple> {

  private static final TupleFactory tupleFactory_ = TupleFactory.getInstance();

  private final int lgK_;
  private final TgtHllType tgtHllType_;
  private Tuple emptySketchTuple_; // this is to cash an empty sketch tuple
  private boolean isFirstCall_ = true; // for logging

  /**
   * Constructor with primitives for the intermediate pass of an Algebraic function.
   *
   * @param lgK parameter controlling the sketch size and accuracy
   * @param tgtHllType HLL type of the resulting sketch
   */
  public AlgebraicIntermediate(final int lgK, final TgtHllType tgtHllType) {
    lgK_ = lgK;
    tgtHllType_ = tgtHllType;
  }

  @Override
  public Tuple exec(final Tuple inputTuple) throws IOException {
    if (isFirstCall_) {
      Logger.getLogger(getClass()).info("Algebraic was used");
      isFirstCall_ = false;
    }
    if (inputTuple == null || inputTuple.size() == 0) {
      return getEmptySketchTuple();
    }
    final DataBag outerBag = (DataBag) inputTuple.get(0);
    if (outerBag == null) {
      return getEmptySketchTuple();
    }
    final Union union = new Union(lgK_);
    for (final Tuple dataTuple: outerBag) {
      final Object f0 = dataTuple.get(0); // inputTuple.bag0.dataTupleN.f0
      if (f0 == null) { continue; }
      if (f0 instanceof DataBag) {
        final DataBag innerBag = (DataBag) f0; // inputTuple.bag0.dataTupleN.f0:bag
        if (innerBag.size() == 0) { continue; }
        // If field 0 of a dataTuple is a Bag, all innerTuples of this inner bag
        // will be passed into the union.
        // It is due to system bagged outputs from multiple mapper Initial functions.
        // The Intermediate stage was bypassed.
        updateUnion(innerBag, union);
      } else if (f0 instanceof DataByteArray) { // inputTuple.bag0.dataTupleN.f0:DBA
        // If field 0 of a dataTuple is a DataByteArray, we assume it is a sketch
        // due to system bagged outputs from multiple mapper Intermediate functions.
        // Each dataTuple.DBA:sketch will merged into the union.
        final DataByteArray dba = (DataByteArray) f0;
        union.update(HllSketch.wrap(Memory.wrap(dba.get())));
      } else { // we should never get here
        throw new IllegalArgumentException("dataTuple.Field0 is not a DataBag or DataByteArray: "
            + f0.getClass().getName());
      }
    }
    return tupleFactory_.newTuple(new DataByteArray(union.getResult(tgtHllType_).toCompactByteArray()));
  }

  abstract void updateUnion(DataBag bag, Union union) throws ExecException;

  private Tuple getEmptySketchTuple() {
    if (emptySketchTuple_ == null) {
      emptySketchTuple_ = tupleFactory_.newTuple(new DataByteArray(
          new HllSketch(lgK_, tgtHllType_).toCompactByteArray()));
    }
    return emptySketchTuple_;
  }

}
