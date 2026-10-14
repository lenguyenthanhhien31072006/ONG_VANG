package com.miniongvang.controller;

import com.miniongvang.web.DispatchEndpoint;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet(urlPatterns = {"/api/orders/*", "/api/drivers", "/api/drivers/*"})
public final class DispatchServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DispatchEndpoint.handle(request, response);
    }

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DispatchEndpoint.handle(request, response);
    }
}
