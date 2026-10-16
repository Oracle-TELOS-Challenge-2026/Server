package com.bintime.chatting.repository;

import com.bintime.chatting.dto.*;
import java.io.StringReader;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!api-docs")
public class ChatRepository {
  private final JdbcTemplate jdbc;

  public ChatRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record User(String id, String role) {}

  public record Room(String id, String customer, String owner, String status, long sequence) {}

  public record Read(long sequence, OffsetDateTime at) {}

  public record Listing(ChatRoomResponse value, OffsetDateTime activity) {}

  public record Replay(String fingerprint, String json, int status, OffsetDateTime expires) {}

  private static final String PROPOSAL_COLUMNS =
      """
      , p.proposal_id as p_id,p.source_message_id as p_source,p.service_id as p_service,
       p.service_description as p_description,p.start_at as p_start,p.end_at as p_end,
       p.duration_minutes as p_duration,p.price as p_price,p.status_code as p_status,
       p.version_no as p_version,p.reservation_id as p_reservation,p.waitlist_id as p_waitlist
      """;

  private static final String PROPOSAL_JOIN =
      " left join chatting_booking_proposals p on p.card_message_id=m.message_id ";

  private static final String MESSAGE_SELECT =
      "select m.*,u.display_name,case when m.sender_id=? then 'CUSTOMER' else 'OWNER' end as"
          + " sender_role"
          + PROPOSAL_COLUMNS
          + " from chatting_messages m join user_accounts u on u.user_id=m.sender_id"
          + PROPOSAL_JOIN;

  public User user(String id, boolean lock) {
    return jdbc
        .query(
            "select user_id,role_code from user_accounts where user_id=?"
                + (lock ? " for update" : ""),
            (r, n) -> new User(r.getString(1), r.getString(2)),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public String storeOwner(String id) {
    return jdbc
        .queryForList("select owner_user_id from store_shops where store_id=?", String.class, id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public String existingRoom(String user, String store) {
    return jdbc
        .queryForList(
            "select room_id from chatting_rooms where customer_id=? and store_id=?",
            String.class,
            user,
            store)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void createRoom(String id, String user, String store, OffsetDateTime now) {
    jdbc.update(
        "insert into chatting_rooms(room_id,customer_id,store_id,created_at) values(?,?,?,?)",
        id,
        user,
        store,
        now);
  }

  public Room room(String id) {
    return jdbc
        .query(
            """
            select r.room_id,r.customer_id,s.owner_user_id,r.status_code,r.last_sequence
            from chatting_rooms r join store_shops s on s.store_id=r.store_id where r.room_id=?
            """,
            (r, n) ->
                new Room(
                    r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5)),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean lockRoom(String id) {
    return !jdbc.queryForList(
            "select room_id from chatting_rooms where room_id=? for update", String.class, id)
        .isEmpty();
  }

  public String storeId(String roomId) {
    return jdbc.queryForObject(
        "select store_id from chatting_rooms where room_id=?", String.class, roomId);
  }

  private static final String VIEW =
      """
      select r.*,s.owner_user_id,s.name as store_name,s.image_url,c.display_name as customer_name,
       coalesce(rp.last_read_sequence,0) as read_sequence,
       coalesce(peer.last_read_sequence,0) as peer_read_sequence,
       (select count(*) from chatting_messages x where x.room_id=r.room_id
         and x.sender_id<>? and x.message_sequence>coalesce(rp.last_read_sequence,0)) as unread_count,
       m.message_id as last_id,m.sender_id as last_sender,m.client_message_id as last_client,
       m.content_text as last_content,m.created_at as last_created,sender.display_name as last_sender_name
      """
          + PROPOSAL_COLUMNS
          + """
          from chatting_rooms r join store_shops s on s.store_id=r.store_id
          join user_accounts c on c.user_id=r.customer_id
          left join chatting_read_positions rp on rp.room_id=r.room_id and rp.user_id=?
          left join chatting_read_positions peer on peer.room_id=r.room_id
           and peer.user_id=case when r.customer_id=? then s.owner_user_id else r.customer_id end
          left join chatting_messages m on m.room_id=r.room_id and m.message_sequence=r.last_sequence
          left join user_accounts sender on sender.user_id=m.sender_id
          """
          + PROPOSAL_JOIN;

  public ChatRoomResponse view(String user, String room) {
    return jdbc
        .query(VIEW + " where r.room_id=?", (r, n) -> listing(r).value(), user, user, user, room)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Listing> list(String user, OffsetDateTime before, String beforeId, int limit) {
    var args = new ArrayList<Object>(List.of(user, user, user, user, user));
    String sql = VIEW + " where (r.customer_id=? or s.owner_user_id=?)";
    if (before != null) {
      sql +=
          " and (coalesce(r.last_message_at,r.created_at)<? or"
              + " (coalesce(r.last_message_at,r.created_at)=? and r.room_id<?))";
      args.addAll(List.of(before, before, beforeId));
    }
    sql +=
        " order by coalesce(r.last_message_at,r.created_at) desc,r.room_id desc fetch first "
            + limit
            + " rows only";
    return jdbc.query(sql, (r, n) -> listing(r), args.toArray());
  }

  private Listing listing(ResultSet r) throws SQLException {
    String customer = r.getString("customer_id");
    ChatMessageResponse last =
        r.getString("last_id") == null
            ? null
            : new ChatMessageResponse(
                r.getString("last_id"),
                r.getString("room_id"),
                r.getLong("last_sequence"),
                new ChatMessageResponse.Sender(
                    r.getString("last_sender"),
                    customer.equals(r.getString("last_sender")) ? "CUSTOMER" : "OWNER",
                    r.getString("last_sender_name")),
                r.getString("last_client"),
                r.getString("p_id") == null ? "TEXT" : "BOOKING_PROPOSAL",
                r.getString("last_content"),
                time(r, "last_created"),
                proposalValue(r));
    var response =
        new ChatRoomResponse(
            r.getString("room_id"),
            new ChatRoomResponse.Store(
                r.getString("store_id"), r.getString("store_name"), r.getString("image_url")),
            new ChatRoomResponse.Customer(customer, r.getString("customer_name")),
            r.getString("status_code"),
            last,
            r.getInt("unread_count"),
            r.getLong("read_sequence"),
            r.getLong("peer_read_sequence"),
            time(r, "created_at"));
    OffsetDateTime activity = time(r, "last_message_at");
    return new Listing(response, activity == null ? response.createdAt() : activity);
  }

  public List<ChatMessageResponse> messages(
      Room room, Long before, long after, long through, int limit, boolean ascending) {
    var args = new ArrayList<Object>(List.of(room.customer(), room.id(), after, through));
    String sql =
        MESSAGE_SELECT + " where m.room_id=? and m.message_sequence>? and m.message_sequence<=?";
    if (before != null) {
      sql += " and m.message_sequence<?";
      args.add(before);
    }
    sql +=
        " order by m.message_sequence "
            + (ascending ? "asc" : "desc")
            + " fetch first "
            + limit
            + " rows only";
    return jdbc.query(sql, this::message, args.toArray());
  }

  public ChatMessageResponse duplicate(Room room, String user, String clientId) {
    return jdbc
        .query(
            MESSAGE_SELECT + " where m.room_id=? and m.sender_id=? and m.client_message_id=?",
            this::message,
            room.customer(),
            room.id(),
            user,
            clientId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public ChatMessageResponse message(Room room, String messageId) {
    return jdbc
        .query(
            MESSAGE_SELECT + " where m.room_id=? and m.message_id=?",
            this::message,
            room.customer(),
            room.id(),
            messageId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public long latestCustomerSequence(Room room) {
    return jdbc.queryForObject(
        "select coalesce(max(message_sequence),0) from chatting_messages where room_id=? and"
            + " sender_id=?",
        Long.class,
        room.id(),
        room.customer());
  }

  public ChatMessageResponse proposal(Room room, String proposalId) {
    return jdbc
        .query(
            MESSAGE_SELECT + " where m.room_id=? and p.proposal_id=?",
            this::message,
            room.customer(),
            room.id(),
            proposalId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<ChatMessageResponse> proposalMessages(Room room, int limit) {
    return jdbc.query(
        MESSAGE_SELECT
            + " where m.room_id=? and p.proposal_id is not null order by m.message_sequence asc"
            + " fetch first "
            + limit
            + " rows only",
        this::message,
        room.customer(),
        room.id());
  }

  public void insertProposal(ChatMessageResponse message) {
    var p = message.bookingProposal();
    jdbc.update(
        "insert into"
            + " chatting_booking_proposals(proposal_id,room_id,card_message_id,source_message_id,service_id,service_description,start_at,end_at,duration_minutes,price,status_code,version_no,reservation_id,waitlist_id,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        p.id(),
        message.roomId(),
        message.id(),
        p.sourceMessageId(),
        p.serviceId(),
        p.serviceDescription(),
        p.startAt(),
        p.endAt(),
        p.durationMinutes(),
        p.price(),
        p.status(),
        p.version(),
        p.reservationId(),
        p.waitlistId(),
        message.createdAt());
  }

  public void confirmProposal(String id, String reservationId, OffsetDateTime now) {
    jdbc.update(
        "update chatting_booking_proposals set"
            + " status_code='CONFIRMED',version_no=version_no+1,reservation_id=?,updated_at=? where"
            + " proposal_id=?",
        reservationId,
        now,
        id);
  }

  public List<ChatMessageResponse> invalidateProposals(
      Room room, String state, List<String> previous, OffsetDateTime now) {
    var args = new ArrayList<Object>();
    args.add(room.id());
    args.addAll(previous);
    String predicate =
        "room_id=? and status_code in ("
            + String.join(",", Collections.nCopies(previous.size(), "?"))
            + ")";
    var ids =
        jdbc.queryForList(
            "select card_message_id from chatting_booking_proposals where " + predicate,
            String.class,
            args.toArray());
    if (ids.isEmpty()) return List.of();
    args.addFirst(now);
    args.addFirst(state);
    jdbc.update(
        "update chatting_booking_proposals set status_code=?,version_no=version_no+1,updated_at=?"
            + " where "
            + predicate,
        args.toArray());
    return ids.stream().map(id -> message(room, id)).toList();
  }

  public void insertMessage(ChatMessageResponse message) {
    jdbc.update(
        connection -> {
          var p =
              connection.prepareStatement(
                  "insert into"
                      + " chatting_messages(message_id,room_id,sender_id,message_sequence,client_message_id,content_text,created_at)"
                      + " values(?,?,?,?,?,?,?)");
          p.setString(1, message.id());
          p.setString(2, message.roomId());
          p.setString(3, message.sender().id());
          p.setLong(4, message.sequence());
          p.setString(5, message.clientMessageId());
          p.setClob(6, new StringReader(message.content()));
          p.setObject(7, message.createdAt());
          return p;
        });
    jdbc.update(
        "update chatting_rooms set last_sequence=?,last_message_at=? where room_id=?",
        message.sequence(),
        message.createdAt(),
        message.roomId());
  }

  public String displayName(String user) {
    return jdbc.queryForObject(
        "select display_name from user_accounts where user_id=?", String.class, user);
  }

  public Read read(String room, String user) {
    return jdbc
        .query(
            "select last_read_sequence,read_at from chatting_read_positions where room_id=? and"
                + " user_id=?",
            (r, n) -> new Read(r.getLong(1), r.getObject(2, OffsetDateTime.class)),
            room,
            user)
        .stream()
        .findFirst()
        .orElse(new Read(0, null));
  }

  public void read(String room, String user, long sequence, OffsetDateTime at) {
    if (jdbc.update(
            "update chatting_read_positions set last_read_sequence=?,read_at=? where room_id=? and"
                + " user_id=?",
            sequence,
            at,
            room,
            user)
        == 0)
      jdbc.update(
          "insert into chatting_read_positions(room_id,user_id,last_read_sequence,read_at)"
              + " values(?,?,?,?)",
          room,
          user,
          sequence,
          at);
  }

  public Replay replay(String user, String operation, String key) {
    return jdbc
        .query(
            "select fingerprint,result_json,http_status,expires_at from chatting_idempotency where"
                + " actor_id=? and operation=? and key_value=?",
            (r, n) ->
                new Replay(
                    r.getString(1),
                    r.getString(2),
                    r.getInt(3),
                    r.getObject(4, OffsetDateTime.class)),
            user,
            operation,
            key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void saveReplay(
      String user,
      String operation,
      String key,
      String hash,
      String json,
      int status,
      OffsetDateTime expires) {
    jdbc.update(
        "delete from chatting_idempotency where actor_id=? and operation=? and key_value=?",
        user,
        operation,
        key);
    jdbc.update(
        connection -> {
          var p =
              connection.prepareStatement(
                  "insert into"
                      + " chatting_idempotency(actor_id,operation,key_value,fingerprint,result_json,http_status,expires_at)"
                      + " values(?,?,?,?,?,?,?)");
          p.setString(1, user);
          p.setString(2, operation);
          p.setString(3, key);
          p.setString(4, hash);
          p.setClob(5, new StringReader(json));
          p.setInt(6, status);
          p.setObject(7, expires);
          return p;
        });
  }

  private ChatMessageResponse message(ResultSet r, int ignored) throws SQLException {
    return new ChatMessageResponse(
        r.getString("message_id"),
        r.getString("room_id"),
        r.getLong("message_sequence"),
        new ChatMessageResponse.Sender(
            r.getString("sender_id"), r.getString("sender_role"), r.getString("display_name")),
        r.getString("client_message_id"),
        r.getString("p_id") == null ? "TEXT" : "BOOKING_PROPOSAL",
        r.getString("content_text"),
        time(r, "created_at"),
        proposalValue(r));
  }

  private ChatBookingProposalResponse proposalValue(ResultSet r) throws SQLException {
    if (r.getString("p_id") == null) return null;
    return new ChatBookingProposalResponse(
        r.getString("p_id"),
        r.getString("p_source"),
        r.getString("p_service"),
        r.getString("p_description"),
        time(r, "p_start"),
        time(r, "p_end"),
        r.getInt("p_duration"),
        r.getLong("p_price"),
        r.getString("p_status"),
        r.getLong("p_version"),
        r.getString("p_reservation"),
        r.getString("p_waitlist"));
  }

  private static OffsetDateTime time(ResultSet r, String name) throws SQLException {
    return r.getObject(name, OffsetDateTime.class);
  }
}
