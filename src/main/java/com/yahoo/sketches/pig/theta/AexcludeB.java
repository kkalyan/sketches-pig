/*
 * Copyright 2016, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.pig.theta;

import static com.yahoo.sketches.Util.DEFAULT_UPDATE_SEED;
import static com.yahoo.sketches.pig.theta.PigUtil.compactOrderedSketchToTuple;
import static com.yahoo.sketches.pig.theta.PigUtil.extractFieldAtIndex;

import java.io.IOException;

import org.apache.pig.EvalFunc;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.DataType;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;

import com.yahoo.memory.Memory;
import com.yahoo.sketches.theta.AnotB;
import com.yahoo.sketches.theta.CompactSketch;
import com.yahoo.sketches.theta.SetOperation;
import com.yahoo.sketches.theta.Sketch;

/**
 * This is a Pig UDF that performs the A-NOT-B Set Operation on two given Sketches. Because this
 * operation is fundamentally asymmetric, it is structured as a single stateless operation rather
 * than stateful as are Union and Intersection UDFs, which can be iterative.
 * The requirement to perform iterative A\B\C\... is rare. If needed, it can be rendered easily by
 * the caller.
 *
 * @author Lee Rhodes
 */
public class AexcludeB extends EvalFunc<Tuple> {
  private final long seed_;

  //TOP LEVEL API
  /**
   * Default constructor to make pig validation happy.  Assumes:
   * <ul>
   * <li><a href="{@docRoot}/resources/dictionary.html#defaultUpdateSeed">See Default Update Seed</a></li>
   * </ul>
   */
  public AexcludeB() {
    this(DEFAULT_UPDATE_SEED);
  }

  /**
   * String constructor.
   *
   * @param seedStr <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>
   */
  public AexcludeB(final String seedStr) {
    this(Long.parseLong(seedStr));
  }

  /**
   * Base constructor.
   *
   * @param seed  <a href="{@docRoot}/resources/dictionary.html#seed">See Update Hash Seed</a>.
   */
  public AexcludeB(final long seed) {
    super();
    this.seed_ = seed;
  }

  // @formatter:off
  /**
   * Top Level Exec Function.
   * <p>
   * This method accepts a <b>Sketch AnotB Input Tuple</b> and returns a
   * <b>Sketch Tuple</b>.
   * </p>
   *
   * <b>Sketch AnotB Input Tuple</b>
   * <ul>
   *   <li>Tuple: TUPLE (Must contain 2 fields): <br>
   *   Java data type: Pig DataType: Description
   *     <ul>
   *       <li>index 0: DataByteArray: BYTEARRAY: Sketch A</li>
   *       <li>index 1: DataByteArray: BYTEARRAY: Sketch B</li>
   *     </ul>
   *   </li>
   * </ul>
   *
   * <p>
   * Any other input tuple will throw an exception!
   * </p>
   *
   * <b>Sketch Tuple</b>
   * <ul>
   *   <li>Tuple: TUPLE (Contains exactly 1 field)
   *     <ul>
   *       <li>index 0: DataByteArray: BYTEARRAY = The serialization of a Sketch object.</li>
   *     </ul>
   *   </li>
   * </ul>
   *
   * @throws ExecException from Pig.
   */
  // @formatter:on

  @Override //TOP LEVEL EXEC
  public Tuple exec(final Tuple inputTuple) throws IOException {
    //The exec is a stateless function.  It operates on the input and returns a result.
    // It can only call static functions.
    final Object objA = extractFieldAtIndex(inputTuple, 0);
    Sketch sketchA = null;
    if (objA != null) {
      final DataByteArray dbaA = (DataByteArray)objA;
      final Memory srcMem = Memory.wrap(dbaA.get());
      sketchA = Sketch.wrap(srcMem, seed_);
    }
    final Object objB = extractFieldAtIndex(inputTuple, 1);
    Sketch sketchB = null;
    if (objB != null) {
      final DataByteArray dbaB = (DataByteArray)objB;
      final Memory srcMem = Memory.wrap(dbaB.get());
      sketchB = Sketch.wrap(srcMem, seed_);
    }

    final AnotB aNOTb = SetOperation.builder().setSeed(seed_).buildANotB();
    aNOTb.update(sketchA, sketchB);
    final CompactSketch compactSketch = aNOTb.getResult(true, null);
    return compactOrderedSketchToTuple(compactSketch);
  }

  @Override
  public Schema outputSchema(final Schema input) {
    if (input != null) {
      try {
        final Schema tupleSchema = new Schema();
        tupleSchema.add(new Schema.FieldSchema("Sketch", DataType.BYTEARRAY));
        return new Schema(new Schema.FieldSchema(getSchemaName(this
            .getClass().getName().toLowerCase(), input), tupleSchema, DataType.TUPLE));
      }
      catch (final FrontendException e) {
        // fall through
      }
    }
    return null;
  }

}
