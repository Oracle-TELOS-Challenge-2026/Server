package com.bintime.user.repository;

import com.bintime.user.dto.NotificationPreferencesResponse;
import com.bintime.user.dto.SaveLocationRequest;
import com.bintime.user.dto.UserLocationResponse;
import com.bintime.user.dto.UserProfileResponse;
import com.bintime.user.dto.UserProfileResponse.Preferences;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Profile("!api-docs")
public class UserProfileRepository {
  private final JdbcClient jdbc;
  private final JsonMapper json;

  public UserProfileRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<UserProfileResponse> findProfile(String userId) {
    return jdbc.sql(
            """
            SELECT user_id, display_name, role_code, latitude, longitude, accuracy_meters,
                   location_source, location_at, location_saved_at, preferences_json, version_no
            FROM user_accounts WHERE user_id = :userId
            """)
        .param("userId", userId)
        .query(
            (rs, row) -> {
              UserLocationResponse location = null;
              if (rs.getObject("latitude") != null) {
                location =
                    new UserLocationResponse(
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"),
                        rs.getObject("accuracy_meters", Integer.class),
                        rs.getString("location_source"),
                        rs.getObject("location_at", OffsetDateTime.class),
                        rs.getObject("location_saved_at", OffsetDateTime.class));
              }
              return new UserProfileResponse(
                  rs.getString("user_id"),
                  rs.getString("display_name"),
                  List.of(rs.getString("role_code")),
                  List.of(),
                  location,
                  json.readValue(rs.getString("preferences_json"), Preferences.class),
                  rs.getLong("version_no"));
            })
        .optional()
        .map(
            profile ->
                new UserProfileResponse(
                    profile.id(),
                    profile.displayName(),
                    profile.roles(),
                    ownedStoreIds(userId),
                    profile.location(),
                    profile.preferences(),
                    profile.version()));
  }

  public boolean exists(String userId) {
    return jdbc.sql("SELECT COUNT(*) FROM user_accounts WHERE user_id = :userId")
            .param("userId", userId)
            .query(Integer.class)
            .single()
        > 0;
  }

  public Optional<NotificationPreferencesResponse> notificationPreferences(String userId) {
    return jdbc.sql(
            "SELECT recommendation_opt_in,recommendation_consent_at,version_no FROM user_accounts"
                + " WHERE user_id=:id")
        .param("id", userId)
        .query(
            (rs, row) ->
                new NotificationPreferencesResponse(
                    "Y".equals(rs.getString(1)) && rs.getObject(2) != null, rs.getLong(3)))
        .optional();
  }

  public int saveNotificationPreferences(String userId, boolean enabled, long expectedVersion) {
    return jdbc.sql(
            "UPDATE user_accounts SET recommendation_opt_in=:enabled,"
                + " recommendation_consent_at=:consent,version_no=version_no+1 WHERE user_id=:id"
                + " AND version_no=:version")
        .param("enabled", enabled ? "Y" : "N")
        .param(
            "consent",
            enabled ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null,
            java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("id", userId)
        .param("version", expectedVersion)
        .update();
  }

  public int updateDisplayName(String userId, String displayName, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE user_accounts
            SET display_name = :displayName, version_no = version_no + 1
            WHERE user_id = :userId AND version_no = :expectedVersion
            """)
        .param("displayName", displayName)
        .param("userId", userId)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int updatePreferences(String userId, Preferences preferences, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE user_accounts
            SET preferences_json = :preferences, version_no = version_no + 1
            WHERE user_id = :userId AND version_no = :expectedVersion
            """)
        .param("preferences", json.writeValueAsString(preferences))
        .param("userId", userId)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int saveLocation(
      String userId,
      SaveLocationRequest location,
      OffsetDateTime observedAt,
      OffsetDateTime savedAt) {
    return jdbc.sql(
            """
            UPDATE user_accounts
            SET latitude = :latitude, longitude = :longitude, accuracy_meters = :accuracy,
                location_source = :source, location_at = :observedAt,
                location_saved_at = :savedAt
            WHERE user_id = :userId AND (location_at IS NULL OR location_at <= :observedAt)
            """)
        .param("latitude", location.latitude())
        .param("longitude", location.longitude())
        .param("accuracy", location.accuracyMeters(), Types.INTEGER)
        .param("source", location.source())
        .param("observedAt", observedAt, Types.TIMESTAMP_WITH_TIMEZONE)
        .param("savedAt", savedAt, Types.TIMESTAMP_WITH_TIMEZONE)
        .param("userId", userId)
        .update();
  }

  public int deleteLocation(String userId) {
    return jdbc.sql(
            """
            UPDATE user_accounts
            SET latitude = NULL, longitude = NULL, accuracy_meters = NULL,
                location_source = NULL, location_at = NULL, location_saved_at = NULL
            WHERE user_id = :userId
            """)
        .param("userId", userId)
        .update();
  }

  private List<String> ownedStoreIds(String userId) {
    return jdbc.sql(
            """
            SELECT store_id FROM store_shops WHERE owner_user_id = :userId ORDER BY store_id
            """)
        .param("userId", userId)
        .query(String.class)
        .list();
  }
}
