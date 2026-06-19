package com.phraselog.expression.dto;

/** Service result that lets the controller return 201 for new saves and 409 for duplicates. */
public record SaveExpressionResult(ExpressionResponse expression, boolean duplicate) {}
