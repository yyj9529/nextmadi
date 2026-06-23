package com.phraselog.review.config;

import com.phraselog.review.repository.JdbcReviewRepository;
import com.phraselog.review.repository.ReviewRepository;
import com.phraselog.review.repository.UnavailableReviewRepository;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the review repository while preserving the no-DB scaffold context. */
@Configuration
public class ReviewConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ReviewConfiguration.class);

  @Bean
  public ReviewRepository reviewRepository(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; /review endpoints are unavailable (no-DB context)");
      return new UnavailableReviewRepository();
    }
    return new JdbcReviewRepository(new JdbcTemplate(dataSource));
  }
}
