package com.example.schedulingsoftware.notify;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns an email's plain text into a simple, phone-friendly HTML email. */
final class EmailHtml {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<]+");

    private EmailHtml() {
    }

    static String render(String subject, String text) {
        StringBuilder body = new StringBuilder();
        String link = null;
        for (String line : text.split("\n", -1)) {
            Matcher m = URL.matcher(line);
            if (m.find()) {
                // A line with a link ("See it anytime: https://…") becomes the button at the bottom.
                link = m.group();
                continue;
            }
            if (line.isBlank()) {
                body.append("<div style=\"height:8px\"></div>");
            } else if (line.startsWith("• ")) {
                body.append("<div style=\"padding:6px 10px;margin:0 0 4px;background:#f3f5fa;border-radius:6px\">")
                        .append(escape(line.substring(2))).append("</div>");
            } else {
                body.append("<p style=\"margin:0 0 12px\">").append(escape(line)).append("</p>");
            }
        }
        String button = link == null ? "" : "<p style=\"margin:20px 0 0\"><a href=\"" + escape(link)
                + "\" style=\"display:inline-block;background:#2457d6;color:#ffffff;text-decoration:none;"
                + "padding:10px 18px;border-radius:8px;font-weight:600\">Open AllianceTel</a></p>";
        return "<!doctype html><html><body style=\"margin:0;padding:24px 12px;background:#f3f5fa;"
                + "font-family:Arial,Helvetica,sans-serif;color:#1d2433;font-size:15px;line-height:1.45\">"
                + "<div style=\"max-width:520px;margin:0 auto;background:#ffffff;border-radius:12px;padding:24px\">"
                + "<div style=\"font-weight:700;color:#0f1d3a;margin-bottom:16px\">AllianceTel</div>"
                + "<h1 style=\"font-size:18px;margin:0 0 16px\">" + escape(subject) + "</h1>"
                + body + button
                + "</div><p style=\"text-align:center;color:#6b7489;font-size:12px;margin:16px 0 0\">"
                + "You get these because you work at AllianceTel. You can turn emails off in the app's account menu."
                + "</p></body></html>";
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
