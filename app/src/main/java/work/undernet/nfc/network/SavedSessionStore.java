package work.undernet.nfc.network;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Private, durable session library. Leaving clears the active choice, not the library. */
public final class SavedSessionStore {
    public static final class Room {
        public final SessionLobbyClient.Session session;
        public final String name;
        Room(SessionLobbyClient.Session session, String name) { this.session = session; this.name = name; }
    }
    private final SharedPreferences preferences;
    public SavedSessionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("saved_sessions", Context.MODE_PRIVATE);
    }
    public synchronized List<Room> all() {
        List<Room> rooms = new ArrayList<>();
        try {
            JSONArray entries = new JSONArray(preferences.getString("rooms", "[]"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                int number = item.getInt("number");
                String secret = item.getString("secret"), code = item.getString("code");
                if (number < 1 || number > 255 || secret.length() < 16 || secret.length() > 256) continue;
                SessionLobbyClient.normalizeCode(code);
                rooms.add(new Room(new SessionLobbyClient.Session(number, secret, code,
                        item.optString("server_name", ""), item.optString("owner", "")), item.getString("name")));
            }
        } catch (JSONException | IllegalArgumentException ignored) { }
        return rooms;
    }
    public synchronized Room active() {
        String code = preferences.getString("active", "");
        for (Room room : all()) if (room.session.code.equals(code)) return room;
        return null;
    }
    public synchronized Room save(SessionLobbyClient.Session session, String defaultName) {
        List<Room> rooms = all();
        for (Room room : rooms) if (room.session.code.equals(session.code)) return room;
        Room room = new Room(session, session.name.isEmpty() ? defaultName : session.name);
        rooms.add(room);
        write(rooms);
        return room;
    }
    public synchronized void activate(Room room) {
        preferences.edit().putString("active", room == null ? "" : room.session.code).commit();
    }
    public synchronized void rename(Room room, String name) {
        if (name.trim().isEmpty() || name.trim().length() > 60) throw new IllegalArgumentException("Invalid name");
        List<Room> rooms = all();
        for (int i = 0; i < rooms.size(); i++) if (rooms.get(i).session.code.equals(room.session.code))
            rooms.set(i, new Room(room.session, name.trim()));
        write(rooms);
    }
    public synchronized void forget(Room room) {
        List<Room> rooms = all();
        rooms.removeIf(item -> item.session.code.equals(room.session.code));
        if (room.session.code.equals(preferences.getString("active", ""))) activate(null);
        write(rooms);
    }
    private void write(List<Room> rooms) {
        JSONArray entries = new JSONArray();
        try {
            for (Room room : rooms) entries.put(new JSONObject().put("number", room.session.number)
                    .put("secret", room.session.secret).put("code", room.session.code).put("name", room.name)
                    .put("owner", room.session.owner).put("server_name", room.session.name));
        } catch (JSONException e) { throw new IllegalStateException("Cannot save session library"); }
        if (!preferences.edit().putString("rooms", entries.toString()).commit())
            throw new IllegalStateException("Cannot save session library");
    }
}
