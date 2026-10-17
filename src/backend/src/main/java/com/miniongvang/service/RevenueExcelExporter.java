package com.miniongvang.service;
import com.miniongvang.dto.report.ReportDtos.Metrics;
import com.miniongvang.dto.report.ReportDtos.Report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal OOXML workbook with numeric cells; uses exactly the API's calculated report. */
public final class RevenueExcelExporter {
    public byte[] export(Report report) throws IOException {
        var output=new ByteArrayOutputStream(); write(report,output); return output.toByteArray();
    }
    public void write(Report report,OutputStream output) throws IOException {
        try (var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
            entry(zip,"[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
            entry(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Bao cao\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            entry(zip,"xl/styles.xml","<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            var sheet=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"4\" topLeftCell=\"A5\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"2\" width=\"27\" customWidth=\"1\"/><col min=\"3\" max=\"6\" width=\"28\" customWidth=\"1\"/><col min=\"7\" max=\"8\" width=\"20\" customWidth=\"1\"/></cols><sheetData>");
            row(sheet,1,new String[]{"Báo cáo doanh thu (VND)",report.muiGio()},false);
            row(sheet,2,new String[]{"Từ ngày",report.tuNgay(),"Đến ngày (không gồm)",report.denNgay(),"Nhóm",report.nhomTheo().name()},false);
            row(sheet,3,new String[]{"Thực thu gồm khoản chưa phân bổ; công nợ là số dư tại cuối mốc, không cộng các kỳ."},false);
            row(sheet,4,new String[]{"Bắt đầu","Kết thúc","Giá trị đơn hoàn tất","Tiền thực thu","Thu chưa phân bổ","Công nợ cuối mốc","Số đơn hoàn tất","Số đơn hủy","Công ty 20%","Tài xế 80%"},false);
            int index=5;
            metrics(sheet,index++,"Tổng kỳ",report.denNgay(),report.tong());
            for(var bucket:report.items()) metrics(sheet,index++,bucket.batDau(),bucket.ketThuc(),bucket.chiTieu());
            sheet.append("</sheetData></worksheet>");entry(zip,"xl/worksheets/sheet1.xml",sheet.toString());
        }
    }
    private static void metrics(StringBuilder xml,int index,String from,String to,Metrics m) {
        row(xml,index,new String[]{from,to,m.giaTriDonHoanTat(),m.tienThucThu(),m.tienThuChuaPhanBo(),m.cuocConPhaiThuCuoiKy(),Long.toString(m.soDonHoanTat()),Long.toString(m.soDonHuy()),m.phanCongTy(),m.phanTaiXe()},true);
    }
    private static void row(StringBuilder xml,int index,String[] values,boolean metrics) {
        xml.append("<row r=\"").append(index).append("\">");
        for(int i=0;i<values.length;i++) {
            String cell=Character.toString((char)('A'+i))+index;
            if(metrics && i>=2) xml.append("<c r=\"").append(cell).append("\" s=\"").append(i==6||i==7?0:1).append("\"><v>").append(values[i]).append("</v></c>");
            else xml.append("<c r=\"").append(cell).append("\" t=\"inlineStr\"><is><t>").append(escape(values[i])).append("</t></is></c>");
        }
        xml.append("</row>");
    }
    private static String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\\\"","&quot;"); }
    private static void entry(ZipOutputStream zip,String name,String xml) throws IOException {
        var entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);
        zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"+xml).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
}
