/*
 * Copyright © 2016 Toru Takahashi. All rights reserved.
 */
package com.torutk.gadget.calendar;

import com.torutk.gadget.support.TinyGadgetSupport;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.skin.DatePickerSkin;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.awt.desktop.SystemSleepEvent;
import java.awt.desktop.SystemSleepListener;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * カレンダーを表示するJavaFXアプリケーションクラス。
 */
public class CalendarGadgetApp extends Application {
    private static final Logger logger = Logger.getLogger(CalendarGadgetApp.class.getName());
    private Stage stage;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> schedule;
    private LocalDate today;
    private Holidays holidays;
    private Pane rootPane;
    private Node calendar;

    @Override
    public void start(Stage primaryStage) {
        var support = TinyGadgetSupport.ofTaskbarless(primaryStage, Preferences.userNodeForPackage(this.getClass()));
        stage = support.getTransparentStage();

        // コマンドライン引数の解析
        parseParameters();

        today = LocalDate.now();
        calendar = createDatePickerPopup(today);

        rootPane = new StackPane();
        rootPane.getChildren().add(calendar);

        var scene = new Scene(rootPane);
        scene.getStylesheets().add(getClass().getResource("Calendar.css").toExternalForm());

        executor = Executors.newSingleThreadScheduledExecutor();
        crossoverDate();
        initResumeProc();

        stage.setScene(scene);
        stage.showingProperty().addListener((observable, oldValue, newValue) -> {
            if (oldValue && !newValue) {
                executor.shutdownNow();
            }
        });
        stage.show();
    }

    /**
     * 引数に指定した日付を「今日」としてカレンダー表示を生成して返却する。
     *
     * @param date 今日
     * @return カレンダー表示ノード
     */
    private Node createDatePickerPopup(LocalDate date) {
        logger.entering(this.getClass().getName(), "createDatePickerPopup");
        var datePicker = new DatePicker(date);
        // 日付セルのファクトリを定義し、曜日名（英名）をスタイルクラスに追加した日付を生成
        datePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().add(DayOfWeek.from(item).getDisplayName(TextStyle.FULL, Locale.US).toLowerCase());
                if (holidays.contains(item)) {
                    getStyleClass().add("holiday");
                }
            }
        });
        return new DatePickerSkin(datePicker).getPopupContent();
    }

    /**
     * 指定した時間から日付が変わるまでの時間（秒）を計算する。
     * <p>
     *
     * @return 日付が変わるまでの時間（秒）、ただし3600秒を超えている場合は、3600を返却する
     */
    private long secondsTillTomorrow(LocalDateTime time) {
        LocalDateTime tomorrow = time.plusDays(1).with(LocalTime.MIDNIGHT);
        long seconds = Duration.between(time, tomorrow).getSeconds();
        return seconds > 3600 ? 3600 : seconds;
    }

    /**
     * 日付更新時にカレンダーを再作成する。
     * <p>
     * 現在の時刻を取得し、その日付が、フィールドtodayの日付より先であれば日付更新処理を実施し、
     * 次の日付更新処理ををスケジュールする。
     * <p>
     * そうでなければ、日付更新処理が何らかの事情で早めに実行されたとみなし、次の日付更新処理の
     * スケジュールのみ実施する。
     */
    private void crossoverDate() {
        var now = LocalDateTime.now();
        if (today.isBefore(now.toLocalDate())) {
            Platform.runLater(() -> {
                today = now.toLocalDate();
                rootPane.getChildren().remove(calendar);
                calendar = createDatePickerPopup(today);
                rootPane.getChildren().add(calendar);
            });
        }
        long seconds = secondsTillTomorrow(now);
        schedule = executor.schedule(this::crossoverDate, seconds, TimeUnit.SECONDS);
        logger.config(() -> String.format("reset crossover date schedule after %d seconds.", seconds));
    }

    /**
     * コマンドラインオプション（名前付き値）を解析する。
     * <p>
     * 表示位置・大きさの指定があれば反映する。
     * 祝日設定ファイルが指定されていれば読み込む。
     */
    private void parseParameters() {
        Map<String, String> params = getParameters().getNamed();
        Platform.runLater(() -> {
            if (params.containsKey("x")) {
                stage.setX(Double.parseDouble(params.get("x")));
            }
            if (params.containsKey("y")) {
                stage.setY(Double.parseDouble(params.get("y")));
            }
            if (params.containsKey("width")) {
                stage.setWidth(Double.parseDouble(params.get("width")));
            }
            if (params.containsKey("height")) {
                stage.setHeight(Double.parseDouble(params.get("height")));
            }
        });
        holidays = new Holidays(params.getOrDefault("holiday", "holidays.conf"));
        verboseLogging(params.getOrDefault("verbose", "v"));
    }

    private void verboseLogging(String vs) {
        Level verbose = vs.length() < 1 ? Level.INFO
                : vs.length() == 1 ? Level.CONFIG
                : vs.length() == 2 ? Level.FINE
                : vs.length() == 3 ? Level.FINER
                : Level.FINEST;
        Logger.getLogger("").setLevel(verbose);
        Arrays.stream(Logger.getLogger("").getHandlers()).forEach(handler -> handler.setLevel(verbose));
    }

    private void initResumeProc() {
        Desktop desktop = Desktop.getDesktop();
        desktop.addAppEventListener(new SystemSleepListener() {
            @Override
            public void systemAboutToSleep(SystemSleepEvent e) {
                logger.info("Detect system about to sleep.");
            }

            @Override
            public void systemAwoke(SystemSleepEvent e) {
                logger.info("Detect system awake.");
                if (schedule != null) {
                    schedule.cancel(true);
                }
                crossoverDate();
            }
        });
    }

    /**
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        System.out.println("Calendar program start");
        launch(args);
    }

}
