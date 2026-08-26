package com.phraselog.auth.email;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EmailIdentityLinkRequest(@JsonProperty("user_id") String userId) {}
