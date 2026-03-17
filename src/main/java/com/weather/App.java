package com.weather;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Popup;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import com.google.gson.*;

public class App extends Application {

    // ── CONFIG ────────────────────────────────────────────
    private static final String API_KEY      = "14cc46604bf225a0e67dd208245c719b";
    private static final String GEO_URL      = "https://api.openweathermap.org/geo/1.0/direct";
    private static final String WEATHER_URL  = "https://api.openweathermap.org/data/2.5/weather";
    private static final String FORECAST_URL = "https://api.openweathermap.org/data/2.5/forecast";
    private static final String ACCENT       = "#f97316";

    // ── STATE ─────────────────────────────────────────────
    private boolean isDark = true;
    private int timezoneOffset = 0;
    private List<JsonObject> forecastData  = new ArrayList<>();
    private JsonObject currentWeather = null; // stores live /weather API response
    private List<JsonObject> geoSuggestions = new ArrayList<>();
    private ScheduledExecutorService clockScheduler;

    // ── UI REFS ───────────────────────────────────────────
    private Scene      scene;
    private ScrollPane root;
    private VBox       mainVBox;
    private Label      logoLabel;
    private TextField  searchField;
    private Button     searchBtn, themeBtn;
    private Popup      suggestionPopup;
    private Label      errorLabel, clockLabel, clockCityLabel;

    // Hero
    private VBox  heroCard;
    private Label heroCityLabel, heroDateLabel, heroTempLabel, heroConditionLabel;
    private Label heroIconLabel, heroTimeBadge;
    private Label humidityVal, windVal, feelsVal, visVal;

    // Hourly
    private HBox   hourlyBox;
    private Canvas tempChart;

    // 7-Day
    private VBox daysBox;

    // Details
    private Label dMax, dMin, dSunrise, dSunset, dPressure, dClouds;
    private List<VBox> detailTiles = new ArrayList<>();

    // ── START ─────────────────────────────────────────────
    @Override
    public void start(Stage stage) {
        heroCard = new VBox(12);
        heroCard.setPadding(new Insets(24));
        heroCard.setMinWidth(360);
        heroCard.setMaxWidth(380);

        mainVBox = new VBox(16);
        mainVBox.setPadding(new Insets(32));
        mainVBox.getChildren().addAll(buildHeader(), buildMainContent());

        root = new ScrollPane(mainVBox);
        root.setFitToWidth(true);
        root.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        scene = new Scene(root, 1100, 760);
        applyTheme();

        stage.setTitle("Skyra — Weather");
        stage.setScene(scene);
        stage.setMinWidth(700);
        stage.setMinHeight(500);
        stage.show();
        stage.setOnCloseRequest(e -> { if (clockScheduler != null) clockScheduler.shutdownNow(); });
    }

    // ── HEADER ────────────────────────────────────────────
    private HBox buildHeader() {
        logoLabel = new Label("Skyra");
        logoLabel.setFont(Font.font("Arial", FontWeight.EXTRA_BOLD, 26));
        Label logoSub = new Label("Weather Dashboard");
        logoSub.setFont(Font.font("Arial", 11));
        logoSub.setOpacity(0.4);
        VBox logoBox = new VBox(2, logoLabel, logoSub);

        searchField = new TextField();
        searchField.setPromptText("Search city — Surat, Tokyo, London...");
        searchField.setFont(Font.font("Arial", 14));
        searchField.setPrefWidth(340);
        searchField.setPadding(new Insets(11, 16, 11, 16));
        searchField.setOnAction(e -> doSearch());
        searchField.textProperty().addListener((obs, o, n) -> {
            if (n.trim().length() >= 2) new Thread(() -> fetchSuggestions(n.trim())).start();
            else Platform.runLater(() -> suggestionPopup.hide());
        });

        searchBtn = new Button("Search");
        searchBtn.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        searchBtn.setPadding(new Insets(11, 22, 11, 22));
        searchBtn.setStyle("-fx-background-color:" + ACCENT + "; -fx-text-fill:white; -fx-background-radius:14; -fx-cursor:hand;");
        searchBtn.setOnAction(e -> doSearch());

        suggestionPopup = new Popup();
        suggestionPopup.setAutoHide(true);

        errorLabel = new Label("");
        errorLabel.setFont(Font.font("Arial", 12));
        errorLabel.setStyle("-fx-text-fill:#ef4444;");

        VBox searchBox = new VBox(6, new HBox(8, searchField, searchBtn), errorLabel);

        themeBtn = new Button("☀️ Light");
        themeBtn.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        themeBtn.setPadding(new Insets(9, 18, 9, 18));
        themeBtn.setStyle("-fx-background-color:" + ACCENT + "; -fx-text-fill:white; -fx-background-radius:20; -fx-cursor:hand;");
        themeBtn.setOnAction(e -> { isDark = !isDark; themeBtn.setText(isDark ? "☀️ Light" : "🌙 Dark"); applyTheme(); if (!forecastData.isEmpty()) drawTempChart(forecastData.subList(0, Math.min(8, forecastData.size()))); });

        clockLabel     = new Label("--:--:--");
        clockLabel.setFont(Font.font("Arial", FontWeight.BOLD, 22));
        clockCityLabel = new Label("");
        clockCityLabel.setFont(Font.font("Arial", 10));
        clockCityLabel.setOpacity(0.4);
        VBox clockBox = new VBox(2, clockLabel, clockCityLabel);
        clockBox.setAlignment(Pos.CENTER_RIGHT);
        clockBox.setVisible(false);

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        HBox header = new HBox(20, logoBox, sp, searchBox, themeBtn, clockBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 16, 0));
        return header;
    }

    // ── MAIN CONTENT ──────────────────────────────────────
    private HBox buildMainContent() {
        buildHeroCard();
        VBox hourlyCard = buildHourlyCard();
        VBox leftCol = new VBox(16, heroCard, hourlyCard);
        leftCol.setMinWidth(380); leftCol.setMaxWidth(380);

        daysBox = new VBox(2);
        VBox forecastCard = wrapCard(daysBox);

        dMax = new Label("--"); dMin = new Label("--");
        dSunrise = new Label("--"); dSunset = new Label("--");
        dPressure = new Label("--"); dClouds = new Label("--");
        GridPane detailsGrid = buildDetailsGrid();

        Label lbl7  = sectionLabel("📅  7-Day Forecast");
        Label lblD  = sectionLabel("🌐  Details");
        Label lblH  = sectionLabel("⏱  24-Hour Forecast");

        VBox rightCol = new VBox(10, lbl7, forecastCard, lblD, detailsGrid);
        HBox.setHgrow(rightCol, Priority.ALWAYS);

        HBox content = new HBox(24, leftCol, rightCol);
        content.setAlignment(Pos.TOP_LEFT);
        return content;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("Arial", FontWeight.BOLD, 11));
        l.setOpacity(0.4);
        return l;
    }

    // ── HERO CARD ─────────────────────────────────────────
    private void buildHeroCard() {
        heroCityLabel = new Label("Search a city");
        heroCityLabel.setFont(Font.font("Arial", FontWeight.BOLD, 22));

        heroDateLabel = new Label("");
        heroDateLabel.setFont(Font.font("Arial", 12));
        heroDateLabel.setOpacity(0.6);

        heroTimeBadge = new Label("☀️ Day");
        heroTimeBadge.setFont(Font.font("Arial", FontWeight.BOLD, 11));
        heroTimeBadge.setPadding(new Insets(4, 12, 4, 12));
        heroTimeBadge.setStyle("-fx-background-color:rgba(255,220,80,0.3); -fx-text-fill:#fde68a; -fx-background-radius:20;");

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(8, new VBox(3, heroCityLabel, heroDateLabel), sp, heroTimeBadge);
        top.setAlignment(Pos.CENTER_LEFT);

        heroIconLabel = new Label("🌤️");
        heroIconLabel.setFont(Font.font(64));
        heroIconLabel.setMaxWidth(Double.MAX_VALUE);
        heroIconLabel.setAlignment(Pos.CENTER);

        heroTempLabel = new Label("--°C");
        heroTempLabel.setFont(Font.font("Arial", FontWeight.EXTRA_BOLD, 72));
        heroTempLabel.setStyle("-fx-text-fill:" + ACCENT + ";");

        heroConditionLabel = new Label("--");
        heroConditionLabel.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        heroConditionLabel.setOpacity(0.6);

        humidityVal = new Label("--"); windVal = new Label("--");
        feelsVal    = new Label("--"); visVal  = new Label("--");
        HBox stats = new HBox(8,
            statTile("💧", humidityVal, "Humid"),
            statTile("💨", windVal,    "Wind"),
            statTile("🌡", feelsVal,   "Feels"),
            statTile("👁", visVal,     "Vis.")
        );

        heroCard.getChildren().setAll(top, heroIconLabel, heroTempLabel, heroConditionLabel, divider(), stats);
    }

    private VBox statTile(String icon, Label val, String lbl) {
        Label ic = new Label(icon); ic.setFont(Font.font(15));
        val.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        Label lb = new Label(lbl); lb.setFont(Font.font("Arial", 9)); lb.setOpacity(0.5);
        VBox t = new VBox(2, ic, val, lb);
        t.setAlignment(Pos.CENTER); t.setPadding(new Insets(10, 6, 10, 6));
        t.setStyle("-fx-background-color:rgba(255,255,255,0.15); -fx-background-radius:14;");
        HBox.setHgrow(t, Priority.ALWAYS); t.setMaxWidth(Double.MAX_VALUE);
        return t;
    }

    // ── HOURLY CARD ───────────────────────────────────────
    private VBox buildHourlyCard() {
        hourlyBox = new HBox(8);
        hourlyBox.setPadding(new Insets(2, 0, 2, 0));
        ScrollPane scroll = new ScrollPane(hourlyBox);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFitToHeight(true);
        scroll.setStyle("-fx-background-color:transparent; -fx-background:transparent;");
        scroll.setPrefHeight(118);
        tempChart = new Canvas(360, 68);
        VBox card = new VBox(10, scroll, tempChart);
        card.setPadding(new Insets(16));
        return card;
    }

    // ── DETAILS GRID ──────────────────────────────────────
    private GridPane buildDetailsGrid() {
        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(10);
        detailTiles.clear();
        VBox t0=detailTile("🔼",dMax,"Max Temp");   detailTiles.add(t0); g.add(t0,0,0);
        VBox t1=detailTile("🔽",dMin,"Min Temp");   detailTiles.add(t1); g.add(t1,1,0);
        VBox t2=detailTile("🌅",dSunrise,"Sunrise");detailTiles.add(t2); g.add(t2,0,1);
        VBox t3=detailTile("🌇",dSunset,"Sunset");  detailTiles.add(t3); g.add(t3,1,1);
        VBox t4=detailTile("🌬",dPressure,"Pressure");detailTiles.add(t4);g.add(t4,0,2);
        VBox t5=detailTile("☁️",dClouds,"Cloud Cover");detailTiles.add(t5);g.add(t5,1,2);
        ColumnConstraints cc = new ColumnConstraints(); cc.setPercentWidth(50);
        ColumnConstraints cc2 = new ColumnConstraints(); cc2.setPercentWidth(50);
        g.getColumnConstraints().addAll(cc, cc2);
        return g;
    }

    private VBox detailTile(String icon, Label val, String lbl) {
        Label ic = new Label(icon); ic.setFont(Font.font(20));
        val.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        Label lb = new Label(lbl); lb.setFont(Font.font("Arial", 10)); lb.setOpacity(0.4);
        VBox t = new VBox(4, ic, val, lb);
        t.setPadding(new Insets(16)); t.setMaxWidth(Double.MAX_VALUE);
        GridPane.setFillWidth(t, true);
        return t;
    }

    private VBox wrapCard(VBox inner) {
        VBox card = new VBox(inner); card.setPadding(new Insets(8)); return card;
    }

    private Separator divider() { Separator s = new Separator(); s.setOpacity(0.15); return s; }

    // ── THEME ─────────────────────────────────────────────
    private void applyTheme() {
        if (isDark) applyNightTheme(); else applyDayTheme();
    }

    private void applyDayTheme() {
        // Beautiful sky gradient background
        String skyGrad = "linear-gradient(to bottom, #87ceeb 0%, #b0d8f5 30%, #d4eeff 60%, #e8f7ff 100%)";
        mainVBox.setStyle("-fx-background-color: " + skyGrad + ";");
        root.setStyle("-fx-background-color: #87ceeb; -fx-background: #87ceeb;");
        scene.setFill(Color.web("#87ceeb"));

        // Glass card style for day
        String glassCard = "-fx-background-color: rgba(255,255,255,0.55); " +
            "-fx-background-radius: 22; " +
            "-fx-border-color: rgba(255,255,255,0.85); " +
            "-fx-border-width: 1.5; " +
            "-fx-border-radius: 22; " +
            "-fx-effect: dropshadow(gaussian, rgba(30,100,180,0.12), 18, 0, 0, 4);";

        // Hero card — vivid sky gradient with glass overlay
        heroCard.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, #4fa3d4, #2980b9, #1a6fa3);" +
            "-fx-background-radius: 24;" +
            "-fx-effect: dropshadow(gaussian, rgba(20,80,150,0.35), 24, 0, 0, 6);"
        );

        // Text colors
        heroCityLabel.setStyle("-fx-text-fill: white;");
        heroDateLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.75);");
        heroTempLabel.setStyle("-fx-text-fill: white;");
        heroConditionLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.75);");
        humidityVal.setStyle("-fx-text-fill: white;");
        windVal.setStyle("-fx-text-fill: white;");
        feelsVal.setStyle("-fx-text-fill: white;");
        visVal.setStyle("-fx-text-fill: white;");

        // All other cards — frosted glass
        applyGlassToCards(glassCard, "#0f172a", "#334155");

        // Search bar
        searchField.setStyle("-fx-background-color: rgba(255,255,255,0.75); " +
            "-fx-border-color: rgba(255,255,255,0.9); " +
            "-fx-border-radius: 14; -fx-background-radius: 14; " +
            "-fx-text-fill: #0f172a; -fx-font-size: 14; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,100,200,0.1), 8, 0, 0, 2);");

        themeBtn.setText("🌙 Dark");
        themeBtn.setStyle("-fx-background-color: rgba(15,23,42,0.75); -fx-text-fill: white; " +
            "-fx-background-radius: 20; -fx-cursor: hand;");

        logoLabel.setStyle("-fx-text-fill: #1a5f8a;");
        clockLabel.setStyle("-fx-text-fill: #0f172a;");
        clockCityLabel.setStyle("-fx-text-fill: #334155;");
    }

    private void applyNightTheme() {
        mainVBox.setStyle("-fx-background-color: #0a0a0f;");
        root.setStyle("-fx-background-color: #0a0a0f; -fx-background: #0a0a0f;");
        scene.setFill(Color.web("#0a0a0f"));

        String nightCard = "-fx-background-color: #111118; " +
            "-fx-background-radius: 22; " +
            "-fx-border-color: rgba(255,255,255,0.06); " +
            "-fx-border-width: 1; " +
            "-fx-border-radius: 22; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 16, 0, 0, 4);";

        heroCard.setStyle("-fx-background-color: #111118; " +
            "-fx-background-radius: 24; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 20, 0, 0, 6);");

        heroCityLabel.setStyle("-fx-text-fill: #f0f4ff;");
        heroDateLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.45);");
        heroTempLabel.setStyle("-fx-text-fill: " + ACCENT + ";");
        heroConditionLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.45);");
        humidityVal.setStyle("-fx-text-fill: #f0f4ff;");
        windVal.setStyle("-fx-text-fill: #f0f4ff;");
        feelsVal.setStyle("-fx-text-fill: #f0f4ff;");
        visVal.setStyle("-fx-text-fill: #f0f4ff;");

        applyGlassToCards(nightCard, "#f0f4ff", "rgba(255,255,255,0.35)");

        searchField.setStyle("-fx-background-color: #1a1a2e; " +
            "-fx-border-color: rgba(255,255,255,0.08); " +
            "-fx-border-radius: 14; -fx-background-radius: 14; " +
            "-fx-text-fill: #f0f4ff; -fx-font-size: 14; " +
            "-fx-prompt-text-fill: #555;");

        themeBtn.setText("☀️ Light");
        themeBtn.setStyle("-fx-background-color: " + ACCENT + "; -fx-text-fill: white; " +
            "-fx-background-radius: 20; -fx-cursor: hand;");

        logoLabel.setStyle("-fx-text-fill: " + ACCENT + ";");
        clockLabel.setStyle("-fx-text-fill: #f0f4ff;");
        clockCityLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.3);");
    }

    private void applyGlassToCards(String cardStyle, String textPrimary, String textSub) {
        // Detail value labels
        for (Label l : new Label[]{dMax, dMin, dSunrise, dSunset, dPressure, dClouds}) {
            l.setStyle("-fx-text-fill: " + textPrimary + ";");
        }
    }

    // ── AUTOCOMPLETE ──────────────────────────────────────
    private void fetchSuggestions(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String json = httpGet(GEO_URL + "?q=" + encoded + "&limit=6&appid=" + API_KEY);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            geoSuggestions.clear();
            for (JsonElement el : arr) geoSuggestions.add(el.getAsJsonObject());
            Platform.runLater(() -> {
                if (!geoSuggestions.isEmpty()) showSuggestionPopup();
                else suggestionPopup.hide();
            });
        } catch (Exception e) {
            Platform.runLater(() -> suggestionPopup.hide());
        }
    }

    private void showSuggestionPopup() {
        String bg       = isDark ? "#1a1a2e"               : "rgba(255,255,255,0.92)";
        String border   = isDark ? "rgba(255,255,255,0.12)" : "rgba(255,255,255,0.95)";
        String txtColor = isDark ? "#f0f4ff"               : "#0f172a";
        String subColor = isDark ? "#6b8aaa"               : "#64748b";
        String hoverBg  = isDark ? "#252542"               : "rgba(79,163,212,0.1)";

        VBox container = new VBox(0);
        container.setStyle(
            "-fx-background-color: " + bg + ";" +
            "-fx-border-color: " + border + ";" +
            "-fx-border-radius: 16; -fx-background-radius: 16;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 24, 0, 0, 8);"
        );
        container.setPrefWidth(searchField.getWidth() + 80);

        for (int i = 0; i < geoSuggestions.size(); i++) {
            JsonObject obj   = geoSuggestions.get(i);
            String name      = obj.get("name").getAsString();
            String country   = obj.has("country") ? obj.get("country").getAsString() : "";
            String state     = obj.has("state")   ? obj.get("state").getAsString()   : "";
            double lat       = obj.get("lat").getAsDouble();
            double lon       = obj.get("lon").getAsDouble();
            String flag      = getFlagEmoji(country);
            String subtitle  = (state.isEmpty() ? "" : state + ", ") + country;

            Label flagLbl = new Label(flag);
            flagLbl.setFont(Font.font(20)); flagLbl.setMinWidth(36);
            flagLbl.setAlignment(Pos.CENTER);

            Label nameLbl = new Label(name);
            nameLbl.setFont(Font.font("Arial", FontWeight.BOLD, 14));
            nameLbl.setStyle("-fx-text-fill: " + txtColor + ";");

            Label subLbl = new Label(subtitle);
            subLbl.setFont(Font.font("Arial", 11));
            subLbl.setStyle("-fx-text-fill: " + subColor + ";");

            VBox nameBox = new VBox(3, nameLbl, subLbl);
            HBox.setHgrow(nameBox, Priority.ALWAYS);

            Label arrow = new Label("→");
            arrow.setFont(Font.font("Arial", FontWeight.BOLD, 14));
            arrow.setStyle("-fx-text-fill: " + subColor + ";"); arrow.setOpacity(0.4);

            HBox row = new HBox(12, flagLbl, nameBox, arrow);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(13, 18, 13, 16));
            row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");

            row.setOnMouseEntered(e -> { row.setStyle("-fx-background-color: " + hoverBg + "; -fx-cursor: hand;"); arrow.setStyle("-fx-text-fill: " + ACCENT + ";"); arrow.setOpacity(1); });
            row.setOnMouseExited(e  -> { row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;"); arrow.setStyle("-fx-text-fill: " + subColor + ";"); arrow.setOpacity(0.4); });

            if (i < geoSuggestions.size() - 1) {
                Separator sep = new Separator(); sep.setOpacity(isDark ? 0.07 : 0.1);
                container.getChildren().addAll(row, sep);
            } else { container.getChildren().add(row); }

            final String fn = name, fc = country;
            final double fl = lat, flo = lon;
            row.setOnMouseClicked(e -> {
                searchField.setText(fn + (fc.isEmpty() ? "" : ", " + fc));
                suggestionPopup.hide();
                new Thread(() -> fetchWeatherByLatLon(fl, flo)).start();
            });
        }
        suggestionPopup.getContent().clear();
        suggestionPopup.getContent().add(container);
        var bounds = searchField.localToScreen(searchField.getBoundsInLocal());
        if (bounds != null) suggestionPopup.show(searchField, bounds.getMinX(), bounds.getMaxY() + 6);
    }

    // ── SEARCH ────────────────────────────────────────────
    private void doSearch() {
        String q = searchField.getText().trim();
        if (q.isEmpty()) { errorLabel.setText("Please enter a city name."); return; }
        // Strip state/country suffixes added by autocomplete e.g. "Boston, MA, US" → "Boston"
        if (q.contains(", ")) {
            q = q.split(", ")[0].trim();
        }
        errorLabel.setText(""); suggestionPopup.hide();
        final String finalQ = q;
        new Thread(() -> fetchWeatherByName(finalQ)).start();
    }

    private void fetchWeatherByName(String city) {
        try {
            String enc = URLEncoder.encode(city, StandardCharsets.UTF_8);
            processWeatherData(
                httpGet(WEATHER_URL  + "?q=" + enc + "&appid=" + API_KEY + "&units=metric"),
                httpGet(FORECAST_URL + "?q=" + enc + "&appid=" + API_KEY + "&units=metric")
            );
        } catch (Exception e) { Platform.runLater(() -> errorLabel.setText("❌ City not found.")); }
    }

    private void fetchWeatherByLatLon(double lat, double lon) {
        try {
            String ll = "?lat=" + lat + "&lon=" + lon;
            processWeatherData(
                httpGet(WEATHER_URL  + ll + "&appid=" + API_KEY + "&units=metric"),
                httpGet(FORECAST_URL + ll + "&appid=" + API_KEY + "&units=metric")
            );
        } catch (Exception e) { Platform.runLater(() -> errorLabel.setText("❌ Network error.")); }
    }

    // ── PROCESS DATA ──────────────────────────────────────
    private void processWeatherData(String curJson, String fJson) {
        JsonObject cur  = JsonParser.parseString(curJson).getAsJsonObject();
        JsonArray fList = JsonParser.parseString(fJson).getAsJsonObject().getAsJsonArray("list");
        forecastData.clear();
        for (JsonElement el : fList) forecastData.add(el.getAsJsonObject());
        timezoneOffset = cur.get("timezone").getAsInt();
        boolean dayTime = isDayTime(cur);
        isDark = !dayTime;
        currentWeather = cur; // save for NOW tile

        Platform.runLater(() -> {
            themeBtn.setText(isDark ? "☀️ Light" : "🌙 Dark");
            applyTheme();
            updateHero(cur, dayTime);
            updateHourly(forecastData.subList(0, Math.min(8, forecastData.size())));
            drawTempChart(forecastData.subList(0, Math.min(8, forecastData.size())));
            updateDays(forecastData);
            updateDetails(cur);
            startClock(timezoneOffset, cur.get("name").getAsString());
        });
    }

    // ── HERO UPDATE ───────────────────────────────────────
    private void updateHero(JsonObject cur, boolean dayTime) {
        String name    = cur.get("name").getAsString();
        String country = cur.getAsJsonObject("sys").get("country").getAsString();
        double temp    = cur.getAsJsonObject("main").get("temp").getAsDouble();
        double feels   = cur.getAsJsonObject("main").get("feels_like").getAsDouble();
        int    hum     = cur.getAsJsonObject("main").get("humidity").getAsInt();
        double wind    = cur.getAsJsonObject("wind").get("speed").getAsDouble();
        int    vis     = cur.get("visibility").getAsInt();
        String desc    = cur.getAsJsonArray("weather").get(0).getAsJsonObject().get("description").getAsString();

        heroCityLabel.setText(name + ", " + country);
        heroDateLabel.setText(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")));
        heroTempLabel.setText(Math.round(temp) + "°C");
        heroConditionLabel.setText(desc.toUpperCase());
        heroIconLabel.setText(getWeatherIcon(desc));
        humidityVal.setText(hum + "%");
        windVal.setText(wind + "m/s");
        feelsVal.setText(Math.round(feels) + "°");
        visVal.setText(String.format("%.1f", vis / 1000.0) + "km");

        // Day/night badge
        if (dayTime) {
            heroTimeBadge.setText("☀️ Day");
            heroTimeBadge.setStyle("-fx-background-color:rgba(255,220,80,0.35); -fx-text-fill:#fde68a; -fx-background-radius:20; -fx-padding:4 12 4 12;");
        } else {
            heroTimeBadge.setText("🌙 Night");
            heroTimeBadge.setStyle("-fx-background-color:rgba(100,130,255,0.3); -fx-text-fill:#a5b4fc; -fx-background-radius:20; -fx-padding:4 12 4 12;");
        }

        // Hero card gradient based on day/night + condition
        String gradient = dayTime ? getDayGradient(desc) : getNightGradient(desc);
        heroCard.setStyle(
            "-fx-background-color: linear-gradient(to bottom right," + gradient + ");" +
            "-fx-background-radius:24;" +
            "-fx-effect: dropshadow(gaussian," + (dayTime ? "rgba(20,80,150,0.3)" : "rgba(0,0,0,0.6)") + ",24,0,0,6);"
        );

        // On daytime, hero text stays white (readable on blue gradient)
        if (dayTime) {
            heroCityLabel.setStyle("-fx-text-fill: white;");
            heroDateLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.75);");
            heroTempLabel.setStyle("-fx-text-fill: white;");
            heroConditionLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.75);");
            humidityVal.setStyle("-fx-text-fill: white;");
            windVal.setStyle("-fx-text-fill: white;");
            feelsVal.setStyle("-fx-text-fill: white;");
            visVal.setStyle("-fx-text-fill: white;");
        }
    }

    private String getDayGradient(String desc) {
        String c = desc.toLowerCase();
        if (c.contains("thunder")) return "#1e293b, #334155, #475569";
        if (c.contains("rain") || c.contains("drizzle")) return "#2c4a6e, #3d6b9e, #4a7fb5";
        if (c.contains("snow")) return "#a8d8f0, #c5e8f8, #e0f4ff";
        if (c.contains("mist") || c.contains("fog")) return "#7a9bb5, #9ab5cc, #bed0e0";
        if (c.contains("clear")) return "#1a7dc4, #2196f3, #42a5f5";
        return "#4682b4, #5b9bd5, #74aee0";
    }

    private String getNightGradient(String desc) {
        String c = desc.toLowerCase();
        if (c.contains("thunder")) return "#070d1a, #0f1a2e, #1a2540";
        if (c.contains("rain") || c.contains("drizzle")) return "#0f172a, #1e293b, #263040";
        if (c.contains("snow")) return "#1e293b, #334155, #475569";
        if (c.contains("mist") || c.contains("fog")) return "#1c2432, #2d3748, #3a4a60";
        if (c.contains("clear")) return "#0c1445, #1a237e, #283593";
        return "#111827, #1f2937, #374151";
    }

    // ── HOURLY UPDATE ─────────────────────────────────────
    private void updateHourly(List<JsonObject> items) {
        hourlyBox.getChildren().clear();
        String itemBg     = isDark ? "#1a1a28" : "rgba(255,255,255,0.55)";
        String itemBorder = isDark ? "rgba(255,255,255,0.06)" : "rgba(255,255,255,0.8)";
        String timeTxt    = isDark ? "rgba(255,255,255,0.4)" : "#334155";
        String tempTxt    = isDark ? "#fff" : "#0f172a";

        // Build display list — inject real current weather as first item if available
        List<JsonObject> displayItems = new ArrayList<>();
        if (currentWeather != null) {
            // Create a synthetic entry from current weather for the NOW tile
            JsonObject nowEntry = new JsonObject();
            nowEntry.add("main",    currentWeather.get("main"));
            nowEntry.add("weather", currentWeather.get("weather"));
            nowEntry.addProperty("pop", 0);
            nowEntry.addProperty("dt_txt", "NOW");
            displayItems.add(nowEntry);
            // Add forecast items skipping first (which overlaps with current)
            displayItems.addAll(items.size() > 1 ? items.subList(1, items.size()) : items);
        } else {
            displayItems.addAll(items);
        }

        for (int i = 0; i < displayItems.size(); i++) {
            JsonObject item = displayItems.get(i);
            double temp = item.getAsJsonObject("main").get("temp").getAsDouble();
            String desc = item.getAsJsonArray("weather").get(0).getAsJsonObject().get("description").getAsString();
            int pop     = item.has("pop") ? (int)(item.get("pop").getAsDouble() * 100) : 0;
            String time = i == 0 ? "NOW" : formatHour(item.get("dt_txt").getAsString());

            Label timeLbl = new Label(time);
            timeLbl.setFont(Font.font("Arial", FontWeight.BOLD, 9));

            Label iconLbl = new Label(getWeatherIcon(desc));
            iconLbl.setFont(Font.font(20));

            Label tempLbl = new Label(Math.round(temp) + "°");
            tempLbl.setFont(Font.font("Arial", FontWeight.BOLD, 13));

            Label popLbl = new Label("💧" + pop + "%");
            popLbl.setFont(Font.font("Arial", 9));
            popLbl.setStyle("-fx-text-fill: #60a5fa;");

            VBox box = new VBox(4, timeLbl, iconLbl, tempLbl, popLbl);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(10, 8, 10, 8));
            box.setMinWidth(72);

            if (i == 0) {
                box.setStyle("-fx-background-color: linear-gradient(to bottom,#f97316,#ea580c); -fx-background-radius:16;");
                timeLbl.setStyle("-fx-text-fill: rgba(255,255,255,0.8);");
                tempLbl.setStyle("-fx-text-fill: white;");
                popLbl.setStyle("-fx-text-fill: rgba(255,255,255,0.7);");
            } else {
                box.setStyle("-fx-background-color: " + itemBg + "; -fx-background-radius:16; " +
                    "-fx-border-color: " + itemBorder + "; -fx-border-radius:16; -fx-border-width:1;");
                timeLbl.setStyle("-fx-text-fill: " + timeTxt + ";");
                tempLbl.setStyle("-fx-text-fill: " + tempTxt + ";");
            }
            hourlyBox.getChildren().add(box);
        }
    }

    // ── TEMPERATURE CHART ─────────────────────────────────
    private void drawTempChart(List<JsonObject> items) {
        GraphicsContext gc = tempChart.getGraphicsContext2D();
        double W = tempChart.getWidth(), H = tempChart.getHeight();
        gc.clearRect(0, 0, W, H);
        if (items.isEmpty()) return;

        double[] temps = items.stream().mapToDouble(i -> i.getAsJsonObject("main").get("temp").getAsDouble()).toArray();
        double min = Arrays.stream(temps).min().getAsDouble() - 2;
        double max = Arrays.stream(temps).max().getAsDouble() + 2;

        double[] xs = new double[temps.length];
        double[] ys = new double[temps.length];
        for (int i = 0; i < temps.length; i++) {
            xs[i] = (i / (double)(temps.length - 1)) * (W - 32) + 16;
            ys[i] = H - 12 - ((temps[i] - min) / (max - min)) * (H - 22);
        }

        LinearGradient grad = new LinearGradient(0,0,0,1,true,CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#f97316", 0.45)), new Stop(1, Color.web("#f97316", 0.0)));
        gc.setFill(grad);
        gc.beginPath(); gc.moveTo(xs[0], ys[0]);
        for (int i = 1; i < temps.length; i++) { double cx = (xs[i-1]+xs[i])/2; gc.bezierCurveTo(cx,ys[i-1],cx,ys[i],xs[i],ys[i]); }
        gc.lineTo(xs[temps.length-1], H); gc.lineTo(xs[0], H); gc.closePath(); gc.fill();

        gc.setStroke(Color.web("#f97316")); gc.setLineWidth(2.5);
        gc.beginPath(); gc.moveTo(xs[0], ys[0]);
        for (int i = 1; i < temps.length; i++) { double cx = (xs[i-1]+xs[i])/2; gc.bezierCurveTo(cx,ys[i-1],cx,ys[i],xs[i],ys[i]); }
        gc.stroke();

        for (int i = 0; i < temps.length; i++) {
            gc.setFill(Color.web("#f97316")); gc.fillOval(xs[i]-3.5, ys[i]-3.5, 7, 7);
            gc.setFill(isDark ? Color.web("#ffffff",0.6) : Color.web("#0f172a",0.75));
            gc.setFont(Font.font("Arial", 9));
            gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
            gc.fillText(Math.round(temps[i]) + "°", xs[i], ys[i] - 8);
        }
    }

    // ── 7-DAY UPDATE ──────────────────────────────────────
    private void updateDays(List<JsonObject> list) {
        LinkedHashMap<String, List<JsonObject>> days = new LinkedHashMap<>();
        for (JsonObject item : list) {
            String date = item.get("dt_txt").getAsString().split(" ")[0];
            days.computeIfAbsent(date, k -> new ArrayList<>()).add(item);
        }
        List<String> keys = new ArrayList<>(days.keySet());
        if (keys.size() > 7) keys = keys.subList(0, 7);

        double gMax = keys.stream().mapToDouble(d -> days.get(d).stream().mapToDouble(i -> i.getAsJsonObject("main").get("temp_max").getAsDouble()).max().orElse(0)).max().orElse(1);
        double gMin = keys.stream().mapToDouble(d -> days.get(d).stream().mapToDouble(i -> i.getAsJsonObject("main").get("temp_min").getAsDouble()).min().orElse(0)).min().orElse(0);

        String nameTxt  = isDark ? "#e2e8f0"               : "#1e3a5f";
        String condTxt  = isDark ? "rgba(255,255,255,0.35)" : "#4a7fb5";
        String highTxt  = isDark ? "#fff"                  : "#0f172a";
        String lowTxt   = isDark ? "rgba(255,255,255,0.3)" : "#64748b";
        String hoverBg  = isDark ? "rgba(255,255,255,0.05)": "rgba(255,255,255,0.55)";
        String activeBg = isDark ? "rgba(249,115,22,0.1)"  : "rgba(249,115,22,0.12)";

        daysBox.getChildren().clear();
        String[] dayNames = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};

        for (int i = 0; i < keys.size(); i++) {
            String date  = keys.get(i);
            List<JsonObject> di = days.get(date);
            JsonObject mid  = di.get(di.size() / 2);
            String desc     = mid.getAsJsonArray("weather").get(0).getAsJsonObject().get("description").getAsString();
            double high     = di.stream().mapToDouble(d -> d.getAsJsonObject("main").get("temp_max").getAsDouble()).max().orElse(0);
            double low      = di.stream().mapToDouble(d -> d.getAsJsonObject("main").get("temp_min").getAsDouble()).min().orElse(0);
            java.time.LocalDate ld = java.time.LocalDate.parse(date);
            String label    = i==0?"Today":i==1?"Tomorrow":dayNames[ld.getDayOfWeek().getValue() % 7];
            double barW     = gMax>gMin ? (high-gMin)/(gMax-gMin) : 0.5;

            Label dayLbl = new Label(label);
            dayLbl.setFont(Font.font("Arial", FontWeight.SEMI_BOLD, 13));
            dayLbl.setPrefWidth(80); dayLbl.setStyle("-fx-text-fill:" + nameTxt + ";");

            Label iconLbl = new Label(getWeatherIcon(desc));
            iconLbl.setFont(Font.font(20)); iconLbl.setPrefWidth(30);

            Label condLbl = new Label(desc);
            condLbl.setFont(Font.font("Arial", 11));
            condLbl.setStyle("-fx-text-fill:" + condTxt + ";");
            HBox.setHgrow(condLbl, Priority.ALWAYS);

            Rectangle barBg = new Rectangle(58, 4);
            barBg.setArcWidth(4); barBg.setArcHeight(4);
            barBg.setFill(isDark ? Color.web("#ffffff",0.1) : Color.web("#1a5f8a",0.15));
            Rectangle barFill = new Rectangle(58 * barW, 4);
            barFill.setArcWidth(4); barFill.setArcHeight(4);
            barFill.setFill(new LinearGradient(0,0,1,0,true,CycleMethod.NO_CYCLE, new Stop(0,Color.web("#3b82f6")), new Stop(1,Color.web("#f97316"))));
            StackPane barPane = new StackPane(barBg, barFill);
            StackPane.setAlignment(barFill, Pos.CENTER_LEFT);

            Label highLbl = new Label(Math.round(high) + "°");
            highLbl.setFont(Font.font("Arial", FontWeight.BOLD, 14));
            highLbl.setStyle("-fx-text-fill:" + highTxt + ";");

            Label lowLbl = new Label(Math.round(low) + "°");
            lowLbl.setFont(Font.font("Arial", 12));
            lowLbl.setStyle("-fx-text-fill:" + lowTxt + ";");

            HBox row = new HBox(10, dayLbl, iconLbl, condLbl, barPane, highLbl, lowLbl);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(11, 14, 11, 14));
            String baseStyle = i==0 ? "-fx-background-color:" + activeBg + "; -fx-background-radius:14; -fx-cursor:hand;" : "-fx-background-color:transparent; -fx-background-radius:14; -fx-cursor:hand;";
            row.setStyle(baseStyle);

            row.setOnMouseEntered(e -> row.setStyle("-fx-background-color:" + hoverBg + "; -fx-background-radius:14; -fx-cursor:hand;"));
            row.setOnMouseExited(e  -> row.setStyle(baseStyle));

            final String fd = date;
            row.setOnMouseClicked(e -> selectDay(fd, row, activeBg, hoverBg));
            daysBox.getChildren().add(row);
        }
    }

    private void selectDay(String date, HBox selected, String activeBg, String hoverBg) {
        for (var node : daysBox.getChildren()) node.setStyle("-fx-background-radius:14; -fx-cursor:hand;");
        selected.setStyle("-fx-background-color:" + activeBg + "; -fx-background-radius:14; -fx-cursor:hand;");
        List<JsonObject> dayItems = new ArrayList<>();
        for (JsonObject item : forecastData) if (item.get("dt_txt").getAsString().startsWith(date)) dayItems.add(item);

        // Check if selected day is today — use full forecast list so NOW tile uses currentWeather
        String todayDate = forecastData.isEmpty() ? "" : forecastData.get(0).get("dt_txt").getAsString().split(" ")[0];
        boolean isToday = date.equals(todayDate);

        if (isToday) {
            // Restore the full current+forecast view so NOW tile shows real current temp
            updateHourly(forecastData.subList(0, Math.min(8, forecastData.size())));
            drawTempChart(forecastData.subList(0, Math.min(8, forecastData.size())));
        } else if (!dayItems.isEmpty()) {
            // For other days, temporarily clear currentWeather so NOW isn't injected
            JsonObject saved = currentWeather;
            currentWeather = null;
            updateHourly(dayItems);
            drawTempChart(dayItems);
            currentWeather = saved;
        }
    }

    // ── DETAILS UPDATE ────────────────────────────────────
    private void updateDetails(JsonObject cur) {
        double max   = cur.getAsJsonObject("main").get("temp_max").getAsDouble();
        double min   = cur.getAsJsonObject("main").get("temp_min").getAsDouble();
        long sunrise = cur.getAsJsonObject("sys").get("sunrise").getAsLong();
        long sunset  = cur.getAsJsonObject("sys").get("sunset").getAsLong();
        int pressure = cur.getAsJsonObject("main").get("pressure").getAsInt();
        int clouds   = cur.getAsJsonObject("clouds").get("all").getAsInt();

        dMax.setText(Math.round(max) + "°C");
        dMin.setText(Math.round(min) + "°C");
        dSunrise.setText(formatUnixTime(sunrise, timezoneOffset));
        dSunset.setText(formatUnixTime(sunset, timezoneOffset));
        dPressure.setText(pressure + " hPa");
        dClouds.setText(clouds + "%");

        // Style detail tiles
        String tileBg  = isDark ? "#111118" : "rgba(255,255,255,0.55)";
        String tileBdr = isDark ? "rgba(255,255,255,0.06)" : "rgba(255,255,255,0.85)";
        String tileStyle = "-fx-background-color:" + tileBg + "; -fx-background-radius:18; " +
            "-fx-border-color:" + tileBdr + "; -fx-border-width:1.5; -fx-border-radius:18; " +
            "-fx-effect: dropshadow(gaussian," + (isDark?"rgba(0,0,0,0.35)":"rgba(30,100,180,0.1)") + ",10,0,0,2);";

        String valTxt = isDark ? "#f0f4ff" : "#0f172a";
        String lblTxt = isDark ? "rgba(255,255,255,0.35)" : "#4a7fb5";
        for (Label l : new Label[]{dMax,dMin,dSunrise,dSunset,dPressure,dClouds}) {
            l.setStyle("-fx-text-fill:" + valTxt + ";");
        }
        for (VBox tile : detailTiles) {
            tile.setStyle(tileStyle);
        }

        // Style hourly card and forecast card
        String cardBg  = isDark ? "#111118" : "rgba(255,255,255,0.5)";
        String cardBdr = isDark ? "rgba(255,255,255,0.06)" : "rgba(255,255,255,0.85)";
        String cardEffect = isDark
            ? "-fx-effect: dropshadow(gaussian,rgba(0,0,0,0.4),14,0,0,3);"
            : "-fx-effect: dropshadow(gaussian,rgba(30,100,180,0.12),14,0,0,3);";
        String cardStyle = "-fx-background-color:" + cardBg + "; -fx-background-radius:22; " +
            "-fx-border-color:" + cardBdr + "; -fx-border-width:1.5; -fx-border-radius:22; " + cardEffect;

        if (hourlyBox.getParent() != null && hourlyBox.getParent().getParent() instanceof VBox hc) hc.setStyle(cardStyle);
        if (daysBox.getParent() instanceof VBox fc) fc.setStyle(cardStyle);
    }

    // ── CLOCK ─────────────────────────────────────────────
    private void startClock(int tz, String cityName) {
        if (clockScheduler != null) clockScheduler.shutdownNow();
        clockScheduler = Executors.newSingleThreadScheduledExecutor();
        clockScheduler.scheduleAtFixedRate(() -> {
            long local = System.currentTimeMillis()/1000 + tz;
            ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(local), ZoneOffset.UTC);
            String t = zdt.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            Platform.runLater(() -> {
                clockLabel.setText(t);
                clockCityLabel.setText(cityName.toUpperCase() + " LOCAL");
                clockLabel.getParent().setVisible(true);
            });
        }, 0, 1, TimeUnit.SECONDS);
    }

    // ── HELPERS ───────────────────────────────────────────
    private boolean isDayTime(JsonObject cur) {
        long tz = cur.get("timezone").getAsLong();
        long now = cur.get("dt").getAsLong() + tz;
        long sr  = cur.getAsJsonObject("sys").get("sunrise").getAsLong() + tz;
        long ss  = cur.getAsJsonObject("sys").get("sunset").getAsLong()  + tz;
        return now >= sr && now <= ss;
    }

    private String getWeatherIcon(String desc) {
        String c = desc.toLowerCase();
        if (c.contains("thunder"))                         return "⛈️";
        if (c.contains("drizzle"))                         return "🌦️";
        if (c.contains("rain"))                            return "🌧️";
        if (c.contains("snow"))                            return "❄️";
        if (c.contains("mist")||c.contains("fog")||c.contains("haze")) return "🌫️";
        if (c.contains("clear"))                           return "☀️";
        if (c.contains("few clouds"))                      return "🌤️";
        if (c.contains("scattered"))                       return "⛅";
        if (c.contains("cloud")||c.contains("overcast"))   return "☁️";
        return "🌡️";
    }

    private String getFlagEmoji(String code) {
        if (code == null || code.length() < 2) return "🌍";
        try {
            int a = Character.codePointAt(code.toUpperCase(), 0) - 0x41 + 0x1F1E6;
            int b = Character.codePointAt(code.toUpperCase(), 1) - 0x41 + 0x1F1E6;
            return new String(Character.toChars(a)) + new String(Character.toChars(b));
        } catch (Exception e) { return "🌍"; }
    }

    private String formatHour(String dtTxt) {
        int h = Integer.parseInt(dtTxt.split(" ")[1].split(":")[0]);
        return h==0?"12AM":h<12?h+"AM":h==12?"12PM":(h-12)+"PM";
    }

    private String formatUnixTime(long ts, int tz) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ts+tz), ZoneOffset.UTC);
        return zdt.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close(); conn.disconnect();
        return sb.toString();
    }

    public static void main(String[] args) { launch(args); }
}