/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.expression;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.h2.api.ErrorCode;
import org.h2.api.IntervalQualifier;
import org.h2.engine.Session;
import org.h2.message.DbException;
import org.h2.table.ColumnResolver;
import org.h2.table.TableFilter;
import org.h2.util.DateTimeFunctions;
import org.h2.util.DateTimeUtils;
import org.h2.value.DataType;
import org.h2.value.Value;
import org.h2.value.ValueDate;
import org.h2.value.ValueInterval;
import org.h2.value.ValueNull;
import org.h2.value.ValueTime;

/**
 * A mathematical operation with intervals.
 */
public class IntervalOperation extends Expression {

    public enum IntervalOpType {
        /**
         * Interval plus interval.
         */
        INTERVAL_PLUS_INTERVAL,

        /**
         * Interval minus interval.
         */
        INTERVAL_MINUS_INTERVAL,

        /**
         * Date-time plus interval.
         */
        DATETIME_PLUS_INTERVAL,

        /**
         * Date-time minus interval.
         */
        DATETIME_MINUS_INTERVAL,

        /**
         * Interval multiplied by numeric.
         */
        INTERVAL_MULTIPLY_NUMERIC,

        /**
         * Interval divided by numeric.
         */
        INTERVAL_DIVIDE_NUMERIC
    }

    private final IntervalOpType opType;
    private Expression left, right;
    private int dataType;

    public IntervalOperation(IntervalOpType opType, Expression left, Expression right) {
        this.opType = opType;
        this.left = left;
        this.right = right;
        switch (opType) {
        case INTERVAL_PLUS_INTERVAL:
        case INTERVAL_MINUS_INTERVAL:
            dataType = Value.getHigherOrder(left.getType(), right.getType());
            break;
        case DATETIME_PLUS_INTERVAL:
        case DATETIME_MINUS_INTERVAL:
        case INTERVAL_MULTIPLY_NUMERIC:
        case INTERVAL_DIVIDE_NUMERIC:
            dataType = left.getType();
        }
    }

    @Override
    public String getSQL() {
        return '(' + left.getSQL() + ' ' + getOperationToken() + ' ' + right.getSQL() + ')';
    }

    private char getOperationToken() {
        switch (opType) {
        case INTERVAL_PLUS_INTERVAL:
        case DATETIME_PLUS_INTERVAL:
            return '+';
        case INTERVAL_MINUS_INTERVAL:
        case DATETIME_MINUS_INTERVAL:
            return '-';
        case INTERVAL_MULTIPLY_NUMERIC:
            return '*';
        case INTERVAL_DIVIDE_NUMERIC:
            return '/';
        default:
            throw DbException.throwInternalError("opType=" + opType);
        }
    }

    @Override
    public Value getValue(Session session) {
        Value l = left.getValue(session);
        Value r = right.getValue(session);
        if (l == ValueNull.INSTANCE || r == ValueNull.INSTANCE) {
            return ValueNull.INSTANCE;
        }
        switch (opType) {
        case INTERVAL_PLUS_INTERVAL:
        case INTERVAL_MINUS_INTERVAL: {
            BigInteger a1 = DateTimeUtils.intervalToAbsolute((ValueInterval) l);
            BigInteger a2 = DateTimeUtils.intervalToAbsolute((ValueInterval) r);
            return DateTimeUtils.intervalFromAbsolute(
                    IntervalQualifier.valueOf(Value.getHigherOrder(l.getType(), r.getType()) - Value.INTERVAL_YEAR),
                    opType == IntervalOpType.INTERVAL_PLUS_INTERVAL ? a1.add(a2) : a1.subtract(a2));
        }
        case DATETIME_PLUS_INTERVAL:
        case DATETIME_MINUS_INTERVAL:
            switch (l.getType()) {
            case Value.TIME: {
                if (DataType.isYearMonthIntervalType(r.getType())) {
                    throw DbException.throwInternalError("type=" + r.getType());
                }
                BigInteger a1 = BigInteger.valueOf(((ValueTime) l).getNanos());
                BigInteger a2 = DateTimeUtils.intervalToAbsolute((ValueInterval) r);
                BigInteger n = opType == IntervalOpType.DATETIME_PLUS_INTERVAL ? a1.add(a2) : a1.subtract(a2);
                if (n.signum() < 0 || n.compareTo(BigInteger.valueOf(DateTimeUtils.NANOS_PER_DAY)) >= 0) {
                    throw DbException.get(ErrorCode.NUMERIC_VALUE_OUT_OF_RANGE_1, n.toString());
                }
                return ValueTime.fromNanos(n.longValue());
            }
            case Value.DATE:
            case Value.TIMESTAMP:
            case Value.TIMESTAMP_TZ:
                if (DataType.isYearMonthIntervalType(r.getType())) {
                    long m = DateTimeUtils.intervalToAbsolute((ValueInterval) r).longValue();
                    if (opType == IntervalOpType.DATETIME_MINUS_INTERVAL) {
                        m = -m;
                    }
                    return DateTimeFunctions.dateadd("MONTH", m, l);
                } else {
                    BigInteger a2 = DateTimeUtils.intervalToAbsolute((ValueInterval) r);
                    if (l.getType() == Value.DATE) {
                        BigInteger a1 = BigInteger
                                .valueOf(DateTimeUtils.absoluteDayFromDateValue(((ValueDate) l).getDateValue()));
                        a2 = a2.divide(BigInteger.valueOf(DateTimeUtils.NANOS_PER_DAY));
                        BigInteger n = opType == IntervalOpType.DATETIME_PLUS_INTERVAL ? a1.add(a2) : a1.subtract(a2);
                        return ValueDate.fromDateValue(DateTimeUtils.dateValueFromAbsoluteDay(n.longValue()));
                    } else {
                        long[] a = DateTimeUtils.dateAndTimeFromValue(l);
                        long absoluteDay = DateTimeUtils.absoluteDayFromDateValue(a[0]);
                        long timeNanos = a[1];
                        BigInteger[] dr = a2.divideAndRemainder(BigInteger.valueOf(DateTimeUtils.NANOS_PER_DAY));
                        if (opType == IntervalOpType.DATETIME_PLUS_INTERVAL) {
                            absoluteDay += dr[0].longValue();
                            timeNanos += dr[1].longValue();
                        } else {
                            absoluteDay -= dr[0].longValue();
                            timeNanos -= dr[1].longValue();
                        }
                        if (timeNanos >= DateTimeUtils.NANOS_PER_DAY) {
                            timeNanos -= DateTimeUtils.NANOS_PER_DAY;
                            absoluteDay++;
                        } else if (timeNanos < 0) {
                            timeNanos += DateTimeUtils.NANOS_PER_DAY;
                            absoluteDay--;
                        }
                        return DateTimeUtils.dateTimeToValue(l, DateTimeUtils.dateValueFromAbsoluteDay(absoluteDay),
                                timeNanos, false);
                    }
                }
            }
            break;
        case INTERVAL_MULTIPLY_NUMERIC:
        case INTERVAL_DIVIDE_NUMERIC: {
            BigDecimal a1 = new BigDecimal(DateTimeUtils.intervalToAbsolute((ValueInterval) l));
            BigDecimal a2 = r.getBigDecimal();
            return DateTimeUtils.intervalFromAbsolute(IntervalQualifier.valueOf(l.getType() - Value.INTERVAL_YEAR),
                    (opType == IntervalOpType.INTERVAL_MULTIPLY_NUMERIC ? a1.multiply(a2) : a1.divide(a2))
                            .toBigInteger());
        }
        }
        throw DbException.throwInternalError("type=" + opType);
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level) {
        left.mapColumns(resolver, level);
        if (right != null) {
            right.mapColumns(resolver, level);
        }
    }

    @Override
    public Expression optimize(Session session) {
        left = left.optimize(session);
        right = right.optimize(session);
        if (left.isConstant() && right.isConstant()) {
            return ValueExpression.get(getValue(session));
        }
        return this;
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        left.setEvaluatable(tableFilter, b);
        right.setEvaluatable(tableFilter, b);
    }

    @Override
    public int getType() {
        return dataType;
    }

    @Override
    public long getPrecision() {
        return Math.max(left.getPrecision(), right.getPrecision());
    }

    @Override
    public int getDisplaySize() {
        return Math.max(left.getDisplaySize(), right.getDisplaySize());
    }

    @Override
    public int getScale() {
        return Math.max(left.getScale(), right.getScale());
    }

    @Override
    public void updateAggregate(Session session) {
        left.updateAggregate(session);
        right.updateAggregate(session);
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        return left.isEverything(visitor) && right.isEverything(visitor);
    }

    @Override
    public int getCost() {
        return left.getCost() + 1 + right.getCost();
    }

    /**
     * Get the left sub-expression of this operation.
     *
     * @return the left sub-expression
     */
    public Expression getLeftSubExpression() {
        return left;
    }

    /**
     * Get the right sub-expression of this operation.
     *
     * @return the right sub-expression
     */
    public Expression getRightSubExpression() {
        return right;
    }

}
