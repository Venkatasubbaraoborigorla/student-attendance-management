package attendance;
import java.io.ByteArrayOutputStream;
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

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

        try {
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
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Could not retrieve attendance"));
        }
    }

    // Download attendance report for all students as a PDF
    @GetMapping("/report/pdf")
    public ResponseEntity<byte[]> downloadAllStudentsPdf() {

        String sql = """
            SELECT s.roll_number, s.name, s.department, s.year,
                   COUNT(a.id) AS total_days,
                   COALESCE(SUM(
                       CASE WHEN a.status = 'Present' THEN 1 ELSE 0 END
                   ), 0) AS present_days,
                   COALESCE(SUM(
                       CASE WHEN a.status = 'Absent' THEN 1 ELSE 0 END
                   ), 0) AS absent_days
            FROM students s
            LEFT JOIN attendance a ON a.student_id = s.id
            GROUP BY s.id, s.roll_number, s.name, s.department, s.year
            ORDER BY s.roll_number
            """;

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Document document = new Document(PageSize.A4.rotate());
            PdfWriter.getInstance(document, output);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
            Font normalFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
            Font headerFont = new Font(Font.HELVETICA, 9, Font.BOLD);

            Paragraph title = new Paragraph(
                "All Students Attendance Report", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            Paragraph generatedDate = new Paragraph(
                "Generated on: " + LocalDate.now(), normalFont);
            generatedDate.setAlignment(Element.ALIGN_CENTER);
            document.add(generatedDate);
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(
                new float[] {1.2f, 2.0f, 1.8f, 0.7f, 1.0f, 1.0f, 1.0f, 1.2f});
            table.setWidthPercentage(100);

            String[] headers = {
                "Roll Number", "Student Name", "Department", "Year",
                "Total Days", "Present", "Absent", "Attendance %"
            };

            for (String heading : headers) {
                PdfPCell cell = new PdfPCell(
                    new Phrase(heading, headerFont));
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setPadding(6);
                table.addCell(cell);
            }

            while (rs.next()) {
                int total = rs.getInt("total_days");
                int present = rs.getInt("present_days");
                int absent = rs.getInt("absent_days");

                double percentage = total == 0
                    ? 0.0 : (present * 100.0 / total);

                table.addCell(new Phrase(
                    safe(rs.getString("roll_number")), normalFont));
                table.addCell(new Phrase(
                    safe(rs.getString("name")), normalFont));
                table.addCell(new Phrase(
                    safe(rs.getString("department")), normalFont));
                table.addCell(new Phrase(
                    String.valueOf(rs.getInt("year")), normalFont));
                table.addCell(new Phrase(
                    String.valueOf(total), normalFont));
                table.addCell(new Phrase(
                    String.valueOf(present), normalFont));
                table.addCell(new Phrase(
                    String.valueOf(absent), normalFont));
                table.addCell(new Phrase(
                    String.format("%.2f%%", percentage), normalFont));
            }

            document.add(table);
            document.close();

            HttpHeaders headersOut = new HttpHeaders();
            headersOut.setContentType(MediaType.APPLICATION_PDF);
            headersOut.setContentDispositionFormData(
                "attachment", "all-students-attendance-report.pdf");

            return ResponseEntity.ok()
                .headers(headersOut)
                .body(output.toByteArray());

        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private String safe(String value) {
        return value == null ? "-" : value;
    }
}
