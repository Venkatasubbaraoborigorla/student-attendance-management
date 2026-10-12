
package attendance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/attendance")
@CrossOrigin
public class AttendanceController {

    private final DataSource dataSource;

    public AttendanceController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostMapping
    public ResponseEntity<?> markAttendance(
            @RequestBody Map<String, String> data) {

        String sql = """
            INSERT INTO attendance (student_id, attendance_date, status)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE status = VALUES(status)
            """;

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            int studentId = Integer.parseInt(data.get("studentId"));
            LocalDate date = LocalDate.parse(data.get("date"));
            String status = data.get("status");

            if (!status.equals("Present") && !status.equals("Absent")) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Status must be Present or Absent"));
            }

            ps.setInt(1, studentId);
            ps.setDate(2, Date.valueOf(date));
            ps.setString(3, status);
            ps.executeUpdate();

            return ResponseEntity.ok(
                Map.of("message", "Attendance saved successfully")
            );

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error",
                    "Could not save attendance. Check student ID and date."));
        }
    }

    @GetMapping
    public ResponseEntity<?> getAttendance(
            @RequestParam(required = false) String date) {

        LocalDate selectedDate = (date == null || date.isBlank())
            ? LocalDate.now() : LocalDate.parse(date);

        String sql = """
            SELECT a.id, a.student_id, s.roll_number, s.name,
                   a.attendance_date, a.status
            FROM attendance a
            JOIN students s ON s.id = a.student_id
            WHERE a.attendance_date = ?
            ORDER BY s.roll_number
            """;

        List<Map<String, Object>> result = new ArrayList<>();

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setDate(1, Date.valueOf(selectedDate));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("studentId", rs.getInt("student_id"));
                    row.put("rollNumber", rs.getString("roll_number"));
                    row.put("name", rs.getString("name"));
                    row.put("date", rs.getDate("attendance_date").toString());
                    row.put("status", rs.getString("status"));
                    result.add(row);
                }
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Could not retrieve attendance"));
        }
    }
}
