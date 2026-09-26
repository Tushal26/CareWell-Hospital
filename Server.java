import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

/* Hospital Queue Management - Java Backend*/
public class Server {

    // File field separator - a control character so real names/phrases
    // typed by users can never accidentally collide with it (unlike "|").
    static final String SEP = "\u0001";

    // A doctor counts as "currently online" if their last heartbeat
    // (set at login and refreshed every ~20s by the dashboard) is more
    // recent than this. Mirrors the same window used on the front-end.
    static final long PRESENCE_TIMEOUT_MS = 45_000;

    static final int AVG_CONSULT_MINUTES = 10; // used for wait-time estimate

    static final Path DATA_DIR = Paths.get("data");
    static final Path DOCTORS_FILE = DATA_DIR.resolve("doctors.txt");
    static final Path QUEUE_FILE = DATA_DIR.resolve("queue.txt");

    // ---------- Data models ----------

    static class Doctor {
        int id;
        String name, username, password, degree, specialty;

        private Doctor() {}

        Doctor(int id, String name, String username, String password, String degree, String specialty) {
            this.id = id;
            this.name = name;
            this.username = username;
            this.password = password;
            this.degree = degree;
            this.specialty = specialty;
        }

        // Public JSON (never includes password)
        String toJson() {
            return toJson(false);
        }

        String toJson(boolean online) {
            return "{"
                    + "\"id\":" + id + ","
                    + "\"name\":\"" + esc(name) + "\","
                    + "\"username\":\"" + esc(username) + "\","
                    + "\"degree\":\"" + esc(degree) + "\","
                    + "\"specialty\":\"" + esc(specialty) + "\","
                    + "\"online\":" + online
                    + "}";
        }

        String toFileLine() {
            return id + SEP + esc(name) + SEP + esc(username) + SEP + esc(password) + SEP + esc(degree) + SEP + esc(specialty);
        }

        static Doctor fromFileLine(String line) {
            String[] p = line.split(SEP, -1);
            Doctor d = new Doctor();
            d.id = Integer.parseInt(p[0]);
            d.name = p[1];
            d.username = p[2];
            d.password = p[3];
            d.degree = p[4];
            d.specialty = p[5];
            return d;
        }
    }

    static class QueueEntry {
        int id;
        String patientName, patientPhone, patientAge;
        int doctorId;
        int queueNumber;
        String status; // "waiting", "in-consultation", "done", "skipped", "cancelled"
        long createdAt;
        String preferredDate, preferredTime; // optional, may be empty strings

        private QueueEntry() {}

        QueueEntry(int id, String patientName, String patientPhone, String patientAge, int doctorId, int queueNumber,
                   String preferredDate, String preferredTime) {
            this.id = id;
            this.patientName = patientName;
            this.patientPhone = patientPhone;
            this.patientAge = patientAge == null ? "" : patientAge;
            this.doctorId = doctorId;
            this.queueNumber = queueNumber;
            this.status = "waiting";
            this.createdAt = System.currentTimeMillis();
            this.preferredDate = preferredDate == null ? "" : preferredDate;
            this.preferredTime = preferredTime == null ? "" : preferredTime;
        }

        String toJson() {
            Doctor d = findDoctor(doctorId);
            return "{"
                    + "\"id\":" + id + ","
                    + "\"patientName\":\"" + esc(patientName) + "\","
                    + "\"patientPhone\":\"" + esc(patientPhone) + "\","
                    + "\"patientAge\":\"" + esc(patientAge) + "\","
                    + "\"doctorId\":" + doctorId + ","
                    + "\"doctorName\":\"" + (d != null ? esc(d.name) : "") + "\","
                    + "\"specialty\":\"" + (d != null ? esc(d.specialty) : "") + "\","
                    + "\"doctorOnline\":" + isOnline(doctorId) + ","
                    + "\"queueNumber\":" + queueNumber + ","
                    + "\"status\":\"" + esc(status) + "\","
                    + "\"preferredDate\":\"" + esc(preferredDate) + "\","
                    + "\"preferredTime\":\"" + esc(preferredTime) + "\","
                    + "\"isDue\":" + isDue(this)
                    + "}";
        }

        String toFileLine() {
            return id + SEP + esc(patientName) + SEP + esc(patientPhone) + SEP + esc(patientAge) + SEP
                    + doctorId + SEP + queueNumber + SEP + esc(status) + SEP + createdAt + SEP
                    + esc(preferredDate) + SEP + esc(preferredTime);
        }

        static QueueEntry fromFileLine(String line) {
            String[] p = line.split(SEP, -1);
            QueueEntry q = new QueueEntry();
            q.id = Integer.parseInt(p[0]);
            q.patientName = p[1];
            q.patientPhone = p[2];
            q.patientAge = p[3];
            q.doctorId = Integer.parseInt(p[4]);
            q.queueNumber = Integer.parseInt(p[5]);
            q.status = p[6];
            q.createdAt = Long.parseLong(p[7]);
            q.preferredDate = p[8];
            q.preferredTime = p[9];
            return q;
        }
    }

    static List<Doctor> doctors = Collections.synchronizedList(new ArrayList<>());
    static List<QueueEntry> queue = Collections.synchronizedList(new ArrayList<>());
    static AtomicInteger doctorIdGen = new AtomicInteger(0);
    static AtomicInteger queueIdGen = new AtomicInteger(0);

    // doctorId -> last heartbeat timestamp (login also counts as a heartbeat)
    static Map<Integer, Long> onlinePresence = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        loadData();

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT","8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new StaticFileHandler());

        server.createContext("/api/doctor/signup", Server::handleDoctorSignup);
        server.createContext("/api/doctor/login", Server::handleDoctorLogin);
        server.createContext("/api/doctor/logout", Server::handleDoctorLogout);
        server.createContext("/api/doctor/heartbeat", Server::handleDoctorHeartbeat);
        server.createContext("/api/doctors", Server::handleDoctorsList);
        server.createContext("/api/doctors/available", Server::handleDoctorsAvailable);

        server.createContext("/api/queue/join", Server::handleQueueJoin);
        server.createContext("/api/queue/status", Server::handleQueueStatus);
        server.createContext("/api/queue/lookup", Server::handleQueueLookup);
        server.createContext("/api/queue/doctor", Server::handleQueueForDoctor);
        server.createContext("/api/queue/next", Server::handleQueueNext);
        server.createContext("/api/queue/call", Server::handleQueueCall);
        server.createContext("/api/queue/done", Server::handleQueueDone);
        server.createContext("/api/queue/skip", Server::handleQueueSkip);
        server.createContext("/api/queue/cancel", Server::handleQueueCancel);
        server.createContext("/api/queue/reassign", Server::handleQueueReassign);

        server.setExecutor(null);
        server.start();

        System.out.println("========================================");
        System.out.println(" Hospital Queue server running!");
        System.out.println(" Open: http://localhost:" + port + "/index.html");
        System.out.println(" Loaded " + doctors.size() + " doctor(s), " + queue.size() + " queue record(s) from disk.");
        System.out.println("========================================");
    }

    // ---------- CORS ----------

    // Every handler calls this first. Returns true (and finishes the
    // response) if this was a CORS preflight OPTIONS request, so the
    // caller should just return without doing anything else.
    static boolean handleCorsPreflight(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    // ---------- Doctor signup / login / presence ----------

    static void handleDoctorSignup(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        String name = extractString(body, "name");
        String username = extractString(body, "username");
        String password = extractString(body, "password");
        String degree = extractString(body, "degree");
        String specialty = extractString(body, "specialty");

        if (name.isEmpty() || username.isEmpty() || password.isEmpty()) {
            sendJson(ex, 400, err("Name, username and password are required")); return;
        }

        for (Doctor d : doctors) {
            if (d.username.equalsIgnoreCase(username)) {
                sendJson(ex, 409, err("Username already taken")); return;
            }
        }

        // Multiple doctors are allowed to share the same specialty - no
        // uniqueness check on specialty, only on username.
        Doctor doc = new Doctor(doctorIdGen.incrementAndGet(), name, username, password, degree, specialty);
        doctors.add(doc);
        saveDoctors();

        sendJson(ex, 200, "{\"success\":true,\"doctor\":" + doc.toJson() + "}");
    }

    static void handleDoctorLogin(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        String username = extractString(body, "username");
        String password = extractString(body, "password");

        for (Doctor d : doctors) {
            if (d.username.equalsIgnoreCase(username) && d.password.equals(password)) {
                onlinePresence.put(d.id, System.currentTimeMillis());
                sendJson(ex, 200, "{\"success\":true,\"doctor\":" + d.toJson(true) + "}");
                return;
            }
        }
        sendJson(ex, 401, err("Invalid username or password"));
    }

    static void handleDoctorLogout(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        int doctorId = (int) extractDouble(body, "doctorId");
        onlinePresence.remove(doctorId);
        sendJson(ex, 200, "{\"success\":true}");
    }

    // The doctor dashboard calls this every ~20 seconds while the tab is
    // open, so onlinePresence stays fresh. If the doctor closes the tab
    // without logging out, their entry simply goes stale after
    // PRESENCE_TIMEOUT_MS and they stop appearing as "available".
    static void handleDoctorHeartbeat(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        int doctorId = (int) extractDouble(body, "doctorId");

        boolean exists = doctors.stream().anyMatch(d -> d.id == doctorId);
        if (!exists) { sendJson(ex, 404, err("Doctor not found")); return; }

        onlinePresence.put(doctorId, System.currentTimeMillis());
        sendJson(ex, 200, "{\"success\":true}");
    }

    static void handleDoctorsList(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < doctors.size(); i++) {
            Doctor d = doctors.get(i);
            sb.append(d.toJson(isOnline(d.id)));
            if (i < doctors.size() - 1) sb.append(",");
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    // Powers the "Book Now" doctor picker: only doctors of the requested
    // specialty who are currently online.
    static void handleDoctorsAvailable(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        String specialty = params.getOrDefault("specialty", "");

        List<Doctor> matches = new ArrayList<>();
        for (Doctor d : doctors) {
            if (d.specialty.equalsIgnoreCase(specialty) && isOnline(d.id)) {
                matches.add(d);
            }
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(matches.get(i).toJson(true));
            if (i < matches.size() - 1) sb.append(",");
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    static boolean isOnline(int doctorId) {
        Long last = onlinePresence.get(doctorId);
        return last != null && (System.currentTimeMillis() - last) < PRESENCE_TIMEOUT_MS;
    }

    static Doctor findDoctor(int doctorId) {
        for (Doctor d : doctors) if (d.id == doctorId) return d;
        return null;
    }

    // ---------- Due-time logic ----------
    // A queue entry with no preferred date/time is always due (a walk-in).
    // A scheduled entry only becomes due once that date/time actually
    // arrives - availability is never judged before that moment.

    static boolean isDue(QueueEntry q) {
        boolean noDate = q.preferredDate == null || q.preferredDate.isEmpty();
        boolean noTime = q.preferredTime == null || q.preferredTime.isEmpty();
        if (noDate && noTime) return true;

        Long target = parseDateTime(q.preferredDate, q.preferredTime);
        if (target == null) return true; // fail-safe: never get permanently stuck hidden
        return System.currentTimeMillis() >= target;
    }

    // preferredDate is expected as yyyy-MM-dd (native <input type="date"> format).
    // preferredTime is expected as "h:mm AM/PM" (matches the front-end's
    // manually-typed time field and its validation pattern).
    static Long parseDateTime(String isoDate, String timeStr) {
        try {
            int h = 0, m = 0;
            if (timeStr != null && !timeStr.trim().isEmpty()) {
                Matcher tm = Pattern.compile("(?i)^(\\d{1,2}):(\\d{2})\\s*(AM|PM)$").matcher(timeStr.trim());
                if (tm.find()) {
                    h = Integer.parseInt(tm.group(1));
                    m = Integer.parseInt(tm.group(2));
                    String period = tm.group(3).toUpperCase();
                    if (period.equals("PM") && h != 12) h += 12;
                    if (period.equals("AM") && h == 12) h = 0;
                } else {
                    return null;
                }
            }

            LocalDate date = (isoDate != null && !isoDate.trim().isEmpty())
                    ? LocalDate.parse(isoDate.trim())
                    : LocalDate.now();

            LocalDateTime dt = date.atTime(h, m);
            return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Patient queue actions (no login needed) ----------

    static void handleQueueJoin(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        String name = extractString(body, "patientName");
        String phone = extractString(body, "patientPhone");
        String age = extractString(body, "patientAge");
        int doctorId = (int) extractDouble(body, "doctorId");
        String preferredDate = extractString(body, "preferredDate");
        String preferredTime = extractString(body, "preferredTime");

        if (name.isEmpty() || phone.isEmpty()) {
            sendJson(ex, 400, err("Name and phone are required")); return;
        }

        Doctor doctor = findDoctor(doctorId);
        if (doctor == null) {
            sendJson(ex, 400, err("Selected doctor not found")); return;
        }

        int nextNumber = 1;
        for (QueueEntry q : queue) {
            if (q.doctorId == doctorId && q.queueNumber >= nextNumber) {
                nextNumber = q.queueNumber + 1;
            }
        }

        QueueEntry entry = new QueueEntry(queueIdGen.incrementAndGet(), name, phone, age, doctorId, nextNumber,
                preferredDate, preferredTime);
        queue.add(entry);
        saveQueue();

        int waitingAhead = countWaitingAhead(entry);
        int estimatedWait = waitingAhead * AVG_CONSULT_MINUTES;

        sendJson(ex, 200, "{"
                + "\"success\":true,"
                + "\"queueId\":" + entry.id + ","
                + "\"queueNumber\":" + entry.queueNumber + ","
                + "\"doctorName\":\"" + esc(doctor.name) + "\","
                + "\"specialty\":\"" + esc(doctor.specialty) + "\","
                + "\"peopleAhead\":" + waitingAhead + ","
                + "\"estimatedWaitMinutes\":" + estimatedWait
                + "}");
    }

    static void handleQueueStatus(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        int queueId = parseIntSafe(params.get("queueId"));

        QueueEntry entry = queue.stream().filter(q -> q.id == queueId).findFirst().orElse(null);
        if (entry == null) { sendJson(ex, 404, err("Queue entry not found")); return; }

        sendJson(ex, 200, buildStatusJson(entry));
    }

    // Primary lookup for patients: by phone number, optionally narrowed by
    // name, since remembering a ticket/queue number isn't realistic.
    static void handleQueueLookup(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        String phone = decodeParam(params.getOrDefault("phone", ""));
        String name = decodeParam(params.getOrDefault("name", ""));

        String phoneDigits = phone.replaceAll("\\D", "");
        String nameLower = name.trim().toLowerCase();

        List<QueueEntry> matches = new ArrayList<>();
        for (QueueEntry q : queue) {
            String qPhoneDigits = q.patientPhone.replaceAll("\\D", "");
            boolean phoneMatch = !phoneDigits.isEmpty() && qPhoneDigits.equals(phoneDigits);
            boolean nameMatch = nameLower.isEmpty() || q.patientName.trim().equalsIgnoreCase(name.trim());
            if (phoneMatch && nameMatch) matches.add(q);
        }
        matches.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(buildStatusJson(matches.get(i)));
            if (i < matches.size() - 1) sb.append(",");
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    static String buildStatusJson(QueueEntry entry) {
        int waitingAhead = countWaitingAhead(entry);
        int estimatedWait = waitingAhead * AVG_CONSULT_MINUTES;
        Doctor d = findDoctor(entry.doctorId);

        return "{"
                + "\"queueId\":" + entry.id + ","
                + "\"queueNumber\":" + entry.queueNumber + ","
                + "\"status\":\"" + esc(entry.status) + "\","
                + "\"doctorId\":" + entry.doctorId + ","
                + "\"doctorName\":\"" + (d != null ? esc(d.name) : "") + "\","
                + "\"specialty\":\"" + (d != null ? esc(d.specialty) : "") + "\","
                + "\"doctorOnline\":" + isOnline(entry.doctorId) + ","
                + "\"isDue\":" + isDue(entry) + ","
                + "\"preferredDate\":\"" + esc(entry.preferredDate) + "\","
                + "\"preferredTime\":\"" + esc(entry.preferredTime) + "\","
                + "\"peopleAhead\":" + waitingAhead + ","
                + "\"estimatedWaitMinutes\":" + estimatedWait
                + "}";
    }

    // Counts how many people with an earlier queue number (for the same
    // doctor) are still waiting/in-consultation AND already due - a
    // future-dated entry that hasn't arrived yet never counts as "ahead".
    static int countWaitingAhead(QueueEntry entry) {
        int count = 0;
        for (QueueEntry q : queue) {
            if (q.doctorId == entry.doctorId
                    && q.queueNumber < entry.queueNumber
                    && (q.status.equals("waiting") || q.status.equals("in-consultation"))
                    && isDue(q)) {
                count++;
            }
        }
        return count;
    }

    // ---------- Doctor-side queue management ----------

    static void handleQueueForDoctor(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"GET".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        int doctorId = parseIntSafe(params.get("doctorId"));

        List<QueueEntry> mine = new ArrayList<>();
        QueueEntry current = null;
        int seenSoFar = 0;
        for (QueueEntry q : queue) {
            if (q.doctorId != doctorId) continue;
            mine.add(q);
            if (q.status.equals("in-consultation")) current = q;
            if (q.status.equals("done")) seenSoFar++;
        }

        List<QueueEntry> dueWaiting = new ArrayList<>();
        for (QueueEntry q : mine) {
            if (q.status.equals("waiting") && isDue(q)) dueWaiting.add(q);
        }
        dueWaiting.sort((a, b) -> Integer.compare(a.queueNumber, b.queueNumber));

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"waitingCount\":").append(dueWaiting.size()).append(",");
        sb.append("\"seenSoFar\":").append(seenSoFar).append(",");
        sb.append("\"totalCount\":").append(mine.size()).append(",");
        sb.append("\"current\":").append(current != null ? current.toJson() : "null").append(",");
        sb.append("\"queue\":[");
        for (int i = 0; i < dueWaiting.size(); i++) {
            sb.append(dueWaiting.get(i).toJson());
            if (i < dueWaiting.size() - 1) sb.append(",");
        }
        sb.append("]}");

        sendJson(ex, 200, sb.toString());
    }

    // "Call Next Patient" - picks the earliest due, waiting entry for this doctor.
    static void handleQueueNext(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        int doctorId = (int) extractDouble(body, "doctorId");

        boolean alreadyInConsultation = queue.stream()
                .anyMatch(q -> q.doctorId == doctorId && q.status.equals("in-consultation"));
        if (alreadyInConsultation) {
            sendJson(ex, 400, err("A patient is already in consultation. Mark them done first."));
            return;
        }

        QueueEntry next = queue.stream()
                .filter(q -> q.doctorId == doctorId && q.status.equals("waiting") && isDue(q))
                .min((a, b) -> Integer.compare(a.queueNumber, b.queueNumber))
                .orElse(null);

        if (next == null) {
            sendJson(ex, 404, err("No patients waiting"));
            return;
        }

        next.status = "in-consultation";
        saveQueue();
        sendJson(ex, 200, "{\"success\":true,\"patient\":" + next.toJson() + "}");
    }

    // Calls one SPECIFIC waiting patient out of order (the "Call" button
    // next to an individual row in the doctor's queue list).
    static void handleQueueCall(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        int doctorId = (int) extractDouble(body, "doctorId");
        int queueId = (int) extractDouble(body, "queueId");

        boolean alreadyInConsultation = queue.stream()
                .anyMatch(q -> q.doctorId == doctorId && q.status.equals("in-consultation"));
        if (alreadyInConsultation) {
            sendJson(ex, 400, err("A patient is already in consultation. Mark them done first."));
            return;
        }

        QueueEntry target = queue.stream()
                .filter(q -> q.id == queueId && q.doctorId == doctorId && q.status.equals("waiting") && isDue(q))
                .findFirst().orElse(null);

        if (target == null) {
            sendJson(ex, 404, err("That patient is not currently waiting for you"));
            return;
        }

        target.status = "in-consultation";
        saveQueue();
        sendJson(ex, 200, "{\"success\":true,\"patient\":" + target.toJson() + "}");
    }

    static void handleQueueDone(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        int queueId = (int) extractDouble(body, "queueId");

        QueueEntry entry = queue.stream().filter(q -> q.id == queueId).findFirst().orElse(null);
        if (entry == null) { sendJson(ex, 404, err("Queue entry not found")); return; }

        entry.status = "done";
        saveQueue();
        sendJson(ex, 200, "{\"success\":true}");
    }

    // No-show: the doctor called them and they weren't there.
    static void handleQueueSkip(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        int queueId = (int) extractDouble(body, "queueId");

        QueueEntry entry = queue.stream().filter(q -> q.id == queueId).findFirst().orElse(null);
        if (entry == null) { sendJson(ex, 404, err("Queue entry not found")); return; }

        entry.status = "skipped";
        saveQueue();
        sendJson(ex, 200, "{\"success\":true}");
    }

    // Patient-initiated cancellation (offered when their scheduled doctor
    // turns out to be unavailable once the appointment is due).
    static void handleQueueCancel(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        int queueId = (int) extractDouble(body, "queueId");

        QueueEntry entry = queue.stream().filter(q -> q.id == queueId).findFirst().orElse(null);
        if (entry == null) { sendJson(ex, 404, err("Queue entry not found")); return; }

        entry.status = "cancelled";
        saveQueue();
        sendJson(ex, 200, "{\"success\":true}");
    }

    // Patient explicitly picks a different doctor after their original one
    // turned out to be unavailable at the due time. This is NEVER done
    // automatically - only in response to the patient's own choice. The
    // appointment moves entirely to the new doctor's queue, as a fresh
    // "right now" entry.
    static void handleQueueReassign(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) { sendJson(ex, 405, err("Method not allowed")); return; }

        String body = readBody(ex);
        int queueId = (int) extractDouble(body, "queueId");
        int newDoctorId = (int) extractDouble(body, "newDoctorId");

        QueueEntry entry = queue.stream().filter(q -> q.id == queueId).findFirst().orElse(null);
        if (entry == null) { sendJson(ex, 404, err("Queue entry not found")); return; }

        Doctor newDoctor = findDoctor(newDoctorId);
        if (newDoctor == null) { sendJson(ex, 400, err("Selected doctor not found")); return; }

        int nextNumber = 1;
        for (QueueEntry q : queue) {
            if (q.doctorId == newDoctorId && q.queueNumber >= nextNumber) {
                nextNumber = q.queueNumber + 1;
            }
        }

        entry.doctorId = newDoctorId;
        entry.queueNumber = nextNumber;
        entry.preferredDate = "";
        entry.preferredTime = "";
        entry.status = "waiting";
        saveQueue();

        sendJson(ex, 200, "{\"success\":true,\"patient\":" + entry.toJson() + "}");
    }

    // ---------- Persistence ----------

    static void loadData() {
        try {
            if (Files.exists(DOCTORS_FILE)) {
                for (String line : Files.readAllLines(DOCTORS_FILE, StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty()) continue;
                    Doctor d = Doctor.fromFileLine(line);
                    doctors.add(d);
                    if (d.id > doctorIdGen.get()) doctorIdGen.set(d.id);
                }
            }
        } catch (Exception e) {
            System.out.println("Could not load doctors.txt: " + e.getMessage());
        }

        try {
            if (Files.exists(QUEUE_FILE)) {
                for (String line : Files.readAllLines(QUEUE_FILE, StandardCharsets.UTF_8)) {
                    if (line.trim().isEmpty()) continue;
                    QueueEntry q = QueueEntry.fromFileLine(line);
                    queue.add(q);
                    if (q.id > queueIdGen.get()) queueIdGen.set(q.id);
                }
            }
        } catch (Exception e) {
            System.out.println("Could not load queue.txt: " + e.getMessage());
        }
    }

    static synchronized void saveDoctors() {
        try {
            Files.createDirectories(DATA_DIR);
            List<String> lines = new ArrayList<>();
            for (Doctor d : doctors) lines.add(d.toFileLine());
            Files.write(DOCTORS_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Could not save doctors.txt: " + e.getMessage());
        }
    }

    static synchronized void saveQueue() {
        try {
            Files.createDirectories(DATA_DIR);
            List<String> lines = new ArrayList<>();
            for (QueueEntry q : queue) lines.add(q.toFileLine());
            Files.write(QUEUE_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Could not save queue.txt: " + e.getMessage());
        }
    }

    // ---------- Static file handler ----------

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            Path filePath = Paths.get("public" + path).normalize();

            if (!filePath.startsWith(Paths.get("public"))) {
                ex.sendResponseHeaders(403, -1);
                return;
            }

            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                byte[] notFound = "404 - File not found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(notFound); }
                return;
            }

            String contentType = guessContentType(filePath.toString());
            byte[] bytes = Files.readAllBytes(filePath);
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    // ---------- Helpers ----------

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String err(String message) {
        return "{\"success\":false,\"error\":\"" + esc(message) + "\"}";
    }

    static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    static String decodeParam(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
