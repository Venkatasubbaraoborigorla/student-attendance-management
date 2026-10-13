
package attendance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import javax.sql.DataSource;

@RestController
@RequestMapping("/api/students")
@CrossOrigin
public class StudentController {

    private final DataSource dataSource;

    public StudentController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostMapping
    public ResponseEntity<?> addStudent(
            @RequestBody Map<String, Object> data) {

        String sql = "INSERT INTO students " +
            "(roll_number, name, department, year, email) " +
            "VALUES (?, ?, ?, ?, ?)";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                 sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, String.valueOf(data.get("rollNumber")));
            ps.setString(2, String.valueOf(data.get("name")));
            ps.setString(3, String.valueOf(data.get("department")));

            Object year = data.get("year");
            if (year == null || year.toString().isBlank()) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, Integer.parseInt(year.toString()));
            }

            Object email = data.get("email");
            ps.setString(5, email == null ? null : email.toString());

            ps.executeUpdate();

            return ResponseEntity.ok(
                Map.of("message", "Student added successfully")
            );

        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Roll number already exists"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Could not add student. Check the entered details."));
        }
    }

    @GetMapping
    public ResponseEntity<?> getStudents() {

        String sql = "SELECT id, roll_number, name, department, year, email " +
                     "FROM students ORDER BY id";

        List<Map<String, Object>> students = new ArrayList<>();

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> student = new java.util.LinkedHashMap<>();
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
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", e.getMessage() == null
            ? "Unknown database error" : e.getMessage()));
}
    }
}
