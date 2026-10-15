
package attendance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/students")
@CrossOrigin
public class StudentController {

    private final DataSource dataSource;

    public StudentController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // Add a new student
    @PostMapping
    public ResponseEntity<?> addStudent(@RequestBody Map<String, Object> data) {
        String sql = "INSERT INTO students "
                + "(roll_number, name, department, year, email) "
                + "VALUES (?, ?, ?, ?, ?)";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     sql, Statement.RETURN_GENERATED_KEYS)) {

            String rollNumber = getRequiredValue(data, "rollNumber");
            String name = getRequiredValue(data, "name");
            String department = getRequiredValue(data, "department");

            if (rollNumber == null || name == null || department == null) {
                return ResponseEntity.badRequest().body(
                        Map.of("error",
                                "Roll number, name and department are required"));
            }

            ps.setString(1, rollNumber);
            ps.setString(2, name);
            ps.setString(3, department);

            Object year = data.get("year");
            if (year == null || year.toString().isBlank()) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                try {
                    ps.setInt(4, Integer.parseInt(year.toString()));
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(
                            Map.of("error", "Year must be a valid number"));
                }
            }

            Object email = data.get("email");
            if (email == null || email.toString().isBlank()) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, email.toString().trim());
            }

            ps.executeUpdate();
            return ResponseEntity.ok(
                    Map.of("message", "Student added successfully"));

        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("error", "Roll number already exists or database constraint failed"));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "Could not add student: " + safeMessage(e)));
        }
    }

    // Get all students
    @GetMapping
    public ResponseEntity<?> getStudents() {
        String sql = "SELECT id, roll_number, name, department, year, email "
                + "FROM students ORDER BY id";

        List<Map<String, Object>> students = new ArrayList<>();

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> student = new LinkedHashMap<>();
                student.put("id", rs.getInt("id"));
                student.put("rollNumber", rs.getString("roll_number"));
                student.put("name", rs.getString("name"));
                student.put("department", rs.getString("department"));
                student.put("year", rs.getObject("year"));
                student.put("email", rs.getString("email"));
                students.add(student);
            }

            return ResponseEntity.ok(students);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "Could not retrieve students: " + safeMessage(e)));
        }
    }

    // Student-wise report: optional fromDate and toDate filters
    // Example: /api/students/1/attendance-report?fromDate=2026-10-01&toDate=2026-10-14
    @GetMapping("/{studentId}/attendance-report")
    public ResponseEntity<?> getStudentAttendanceReport(
            @PathVariable int studentId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {

        if (studentId <= 0) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Invalid student ID"));
        }

        LocalDate from = null;
        LocalDate to = null;

        try {
            if (fromDate != null && !fromDate.isBlank()) {
                from = LocalDate.parse(fromDate);
            }
            if (toDate != null && !toDate.isBlank()) {
                to = LocalDate.parse(toDate);
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Dates must use YYYY-MM-DD format"));
        }

        if (from != null && to != null && from.isAfter(to)) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "From date must be before or equal to To date"));
        }

        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.roll_number, s.name, "
                + "a.attendance_date, a.status "
                + "FROM students s "
                + "LEFT JOIN attendance a ON s.id = a.student_id ");

        List<Object> params = new ArrayList<>();
        sql.append("WHERE s.id = ? ");
        params.add(studentId);

        if (from != null) {
            sql.append("AND (a.attendance_date >= ? OR a.attendance_date IS NULL) ");
            params.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            sql.append("AND (a.attendance_date <= ? OR a.attendance_date IS NULL) ");
            params.add(java.sql.Date.valueOf(to));
        }

        sql.append("ORDER BY a.attendance_date DESC");

        List<Map<String, Object>> records = new ArrayList<>();

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            String rollNumber = null;
            String name = null;
            int present = 0;
            int absent = 0;

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rollNumber = rs.getString("roll_number");
                    name = rs.getString("name");

                    java.sql.Date date = rs.getDate("attendance_date");
                    String status = rs.getString("status");

                    if (date != null && status != null) {
                        Map<String, Object> record = new LinkedHashMap<>();
                        record.put("date", date.toString());
                        record.put("status", status);
                        records.add(record);

                        if ("present".equalsIgnoreCase(status)) {
                            present++;
                        } else if ("absent".equalsIgnoreCase(status)) {
                            absent++;
                        }
                    }
                }
            }

            if (rollNumber == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        Map.of("error", "Student not found"));
            }

            int totalDays = present + absent;
            double percentage = totalDays == 0 ? 0.0
                    : Math.round(present * 10000.0 / totalDays) / 100.0;

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("studentId", studentId);
            report.put("rollNumber", rollNumber);
            report.put("studentName", name);
            report.put("fromDate", fromDate == null ? "" : fromDate);
            report.put("toDate", toDate == null ? "" : toDate);
            report.put("totalDays", totalDays);
            report.put("presentDays", present);
            report.put("absentDays", absent);
            report.put("attendancePercentage", percentage);
            report.put("records", records);

            return ResponseEntity.ok(report);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("error", "Could not retrieve attendance report: " + safeMessage(e)));
        }
    }

    private String getRequiredValue(Map<String, Object> data, String key) {
        Object value = data.get(key);

        if (value == null || value.toString().isBlank()) {
            return null;
        }

        return value.toString().trim();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank())
                ? "Unknown database error" : message;
    }
}
