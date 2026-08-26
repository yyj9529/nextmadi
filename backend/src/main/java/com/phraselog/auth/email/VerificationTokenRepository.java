package com.phraselog.auth.email;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface VerificationTokenRepository {

  void create(String identifier, String token, OffsetDateTime expires);

  /**
   * Deletes the row and returns what it held, in one statement. Returning it after a separate
   * delete would let two concurrent clicks both read the row before either delete landed, which is
   * exactly the replay this store exists to prevent.
   */
  Optional<VerificationTokenRow> consume(String identifier, String token);

  int countUnexpired(String identifier);

  int purgeExpired();
}
