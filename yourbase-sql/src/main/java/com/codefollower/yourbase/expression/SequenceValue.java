/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.yourbase.expression;

import com.codefollower.yourbase.dbobject.Sequence;
import com.codefollower.yourbase.dbobject.table.ColumnResolver;
import com.codefollower.yourbase.dbobject.table.TableFilter;
import com.codefollower.yourbase.engine.Session;
import com.codefollower.yourbase.message.DbException;
import com.codefollower.yourbase.value.Value;
import com.codefollower.yourbase.value.ValueInt;
import com.codefollower.yourbase.value.ValueLong;

/**
 * Wraps a sequence when used in a statement.
 */
public class SequenceValue extends Expression {

    private final Sequence sequence;

    public SequenceValue(Sequence sequence) {
        this.sequence = sequence;
    }

    public Value getValue(Session session) {
        long value = sequence.getNext(session);
        session.setLastIdentity(ValueLong.get(value));
        return ValueLong.get(value);
    }

    public int getType() {
        return Value.LONG;
    }

    public void mapColumns(ColumnResolver resolver, int level) {
        // nothing to do
    }

    public Expression optimize(Session session) {
        return this;
    }

    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        // nothing to do
    }

    public int getScale() {
        return 0;
    }

    public long getPrecision() {
        return ValueInt.PRECISION;
    }

    public int getDisplaySize() {
        return ValueInt.DISPLAY_SIZE;
    }

    public String getSQL(boolean isDistributed) {
        return "(NEXT VALUE FOR " + sequence.getSQL() +")";
    }

    public void updateAggregate(Session session) {
        // nothing to do
    }

    public boolean isEverything(ExpressionVisitor visitor) {
        switch(visitor.getType()) {
        case ExpressionVisitor.EVALUATABLE:
        case ExpressionVisitor.OPTIMIZABLE_MIN_MAX_COUNT_ALL:
        case ExpressionVisitor.NOT_FROM_RESOLVER:
        case ExpressionVisitor.GET_COLUMNS:
            return true;
        case ExpressionVisitor.DETERMINISTIC:
        case ExpressionVisitor.READONLY:
        case ExpressionVisitor.INDEPENDENT:
        case ExpressionVisitor.QUERY_COMPARABLE:
            return false;
        case ExpressionVisitor.SET_MAX_DATA_MODIFICATION_ID:
            visitor.addDataModificationId(sequence.getModificationId());
            return true;
        case ExpressionVisitor.GET_DEPENDENCIES:
            visitor.addDependency(sequence);
            return true;
        default:
            throw DbException.throwInternalError("type="+visitor.getType());
        }
    }

    public int getCost() {
        return 1;
    }

}
