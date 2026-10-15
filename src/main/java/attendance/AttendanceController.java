
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
import java.io.ByteArrayOutputStream;

import javax.sql.DataSource;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.PageSize;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

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

            if (!"Present".equals(status) && !"Absent".equals(status)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error",
                        "Status must be Present or Absent"));
            }

            ps.setInt(1, studentId);
            ps.setDate(2, Date.valueOf(date));
            ps.setString(3, status);
            ps.executeUpdate();

            return ResponseEntity.ok(
                Map.of("message", "Attendance saved successfully"));

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error",
                    "Could not save attendance. Check student ID and date."));
        }
    }

    @GetMapping
    public ResponseEntity<?> getAttendance(
            @RequestParam(required = false) String date) {

        LocalDate selectedDate =
            (date == null || date.isBlank())
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
                    row.put("date",
                        rs.getDate("attendance_date").toString());
                    row.put("status", rs.getString("status"));
                    result.add(row);
                }
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error",
                    "Could not retrieve attendance"));
        }
    }

    @GetMapping("/report/pdf")
    public ResponseEntity<byte[]> downloadAttendancePdf() {

        String sql = """
            SELECT s.id, s.roll_number, s.name, s.department, s.year,
                   COUNT(a.id) AS total_days,
                   COALESCE(SUM(
                       CASE WHEN a.status = 'Present'
                       THEN 1 ELSE 0 END), 0) AS present_days,
                   COALESCE(SUM(
                       CASE WHEN a.status = 'Absent'
                       THEN 1 ELSE 0 END), 0) AS absent_days
            FROM students s
            LEFT JOIN attendance a ON a.student_id = s.id
            GROUP BY s.id, s.roll_number, s.name,
                     s.department, s.year
            ORDER BY s.roll_number
            """;

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery();
             ByteArrayOutputStream output =
                 new ByteArrayOutputStream()) {

            Document document = new Document(PageSize.A4.rotate());
            PdfWriter.getInstance(document, output);
            document.open();

            Font titleFont = FontFactory.getFont(
                FontFactory.HELVETICA_BOLD, 16);

            Paragraph title = new Paragraph(
                "All Students Attendance Report", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(
                new float[] {2, 3, 2, 1, 2, 2, 2, 2});
            table.setWidthPercentage(100);

            String[] headings = {
                "Roll Number", "Student Name", "Department", "Year",
                "Total Days", "Present", "Absent", "Attendance %"
            };

            for (String heading : headings) {
                table.addCell(heading);
            }

            while (rs.next()) {
                int total = rs.getInt("total_days");
                int present = rs.getInt("present_days");
                int absent = rs.getInt("absent_days");

                double percentage = total == 0
                    ? 0.0 : present * 100.0 / total;

                table.addCell(rs.getString("roll_number"));
                table.addCell(rs.getString("name"));
                table.addCell(rs.getString("department"));
                table.addCell(String.valueOf(rs.getInt("year")));
                table.addCell(String.valueOf(total));
                table.addCell(String.valueOf(present));
                table.addCell(String.valueOf(absent));
                table.addCell(String.format("%.2f%%", percentage));
            }

            document.add(table);
            document.close();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.set(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"all-students-attendance-report.pdf\"");

            return ResponseEntity.ok()
                .headers(headers)
                .body(output.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
