/*
 * Copyright 2015, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */
package com.yahoo.sketches.pig.tuple;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.apache.pig.EvalFunc;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.Tuple;

import com.yahoo.sketches.tuple.ArrayOfDoublesUpdatableSketch;
import com.yahoo.sketches.tuple.ArrayOfDoublesUpdatableSketchBuilder;
import com.yahoo.sketches.tuple.ArrayOfDoublesSketch;
import com.yahoo.sketches.tuple.ArrayOfDoublesUnion;
import com.yahoo.sketches.tuple.ArrayOfDoublesSetOperationBuilder;

/**
 * Class used to calculate the intermediate pass (combiner) or the final pass
 * (reducer) of an Algebraic sketch operation. This may be called multiple times
 * (from the mapper and from the reducer). It will receive a bag of values
 * returned by either the Intermediate stage or the Initial stages, so
 * it needs to be able to differentiate between and interpret both types.
 */
abstract class DataToArrayOfDoublesSketchAlgebraicIntermediateFinal extends EvalFunc<Tuple> {
  private int sketchSize_;
  private float samplingProbability_;
  private int numValues_;
  private boolean isFirstCall_ = true;

  /**
   * Default constructor to make pig validation happy.
   */
  DataToArrayOfDoublesSketchAlgebraicIntermediateFinal() {}
  
  DataToArrayOfDoublesSketchAlgebraicIntermediateFinal(int sketchSize, int numValues) {
    this(sketchSize, 1f, numValues);
  }
  
  DataToArrayOfDoublesSketchAlgebraicIntermediateFinal(int sketchSize, float samplingProbability, int numValues) {
    sketchSize_ = sketchSize;
    samplingProbability_ = samplingProbability;
    numValues_ = numValues;
  }
  
  @Override
  public Tuple exec(Tuple inputTuple) throws IOException {
    if (isFirstCall_) {
      Logger.getLogger(getClass()).info("algebraic is used"); // this is to see in the log which way was used by Pig
      isFirstCall_ = false;
    }
    ArrayOfDoublesUpdatableSketch sketch = null;
    ArrayOfDoublesUnion union = new ArrayOfDoublesSetOperationBuilder().setNominalEntries(sketchSize_).setNumberOfValues(numValues_).buildUnion();

    DataBag bag = (DataBag) inputTuple.get(0);
    if (bag == null) {
      throw new IllegalArgumentException("InputTuple.Field0: Bag may not be null");
    }

    for (Tuple dataTuple: bag) {
      Object item = dataTuple.get(0);
      if (item instanceof DataBag) {
        // this is a bag from the Initial function.
        // just insert each item of the tuple into the sketch
        if (sketch == null) sketch = new ArrayOfDoublesUpdatableSketchBuilder().setNominalEntries(sketchSize_).setSamplingProbability(samplingProbability_).setNumberOfValues(numValues_).build();
        DataToArrayOfDoublesSketchBase.updateSketch((DataBag)item, sketch, numValues_);
      } else if (item instanceof DataByteArray) {
        // This is a sketch from a prior call to the 
        // Intermediate function. merge it with the 
        // current sketch.
        ArrayOfDoublesSketch incomingSketch = Util.deserializeArrayOfDoublesSketchFromTuple(dataTuple);
        union.update(incomingSketch);
      } else {
        // we should never get here.
        throw new IllegalArgumentException("InputTuple.Field0: Bag contains unrecognized types: " + item.getClass().getName());
      }
    }
    if (sketch != null) union.update(sketch);
    return Util.serializeArrayOfDoublesSketchToTuple(union.getResult());
  }
}