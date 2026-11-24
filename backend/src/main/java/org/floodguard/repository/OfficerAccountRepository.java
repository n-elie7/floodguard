package org.floodguard.repository;

import org.floodguard.model.OfficerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OfficerAccountRepository extends JpaRepository<OfficerAccount, String> {
  @Modifying
  @Transactional
  @Query(
      value =
          """
          INSERT INTO officer_accounts(username, password_hash, must_change_password)
          VALUES (:username, :hash, true)
          ON CONFLICT (username) DO NOTHING
          """,
      nativeQuery = true)
  void insertIfAbsent(@Param("username") String username, @Param("hash") String hash);
}
