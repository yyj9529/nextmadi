package com.phraselog.practice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** JSON body for text-mode POST /practice/sessions/{id}/turns. */
public record SubmitPracticeTurnRequest(@JsonProperty("text_content") String textContent) {}
