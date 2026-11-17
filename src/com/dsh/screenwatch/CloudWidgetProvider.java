package com.dsh.screenwatch;

import android.app.PendingIntent;
import android.os.Bundle;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.RemoteViews;

/** Home-screen widget: shows the chosen cloud-gaming app and launches it on tap. */
public class CloudWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            update(context, manager, id, null);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                         int appWidgetId, Bundle newOptions) {
        update(context, manager, appWidgetId, newOptions);
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, CloudWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(provider)) {
            update(context, manager, id, null);
        }
    }

    static void update(Context context, AppWidgetManager manager, int widgetId, Bundle options) {
        if (options == null) {
            options = manager.getAppWidgetOptions(widgetId);
        }
        int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180);
        int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 70);
        boolean compact = minWidth < 150 || minHeight < 56;
        RemoteViews views = new RemoteViews(context.getPackageName(),
                compact ? R.layout.widget_cloud_compact : R.layout.widget_cloud);
        SharedPreferences p = context.getSharedPreferences(ScreenWatchService.PREF, Context.MODE_PRIVATE);
        String pkg = p.getString("cloud_app_package", null);
        String label = p.getString("cloud_app_label", null);

        if (pkg != null) {
            views.setTextViewText(R.id.widget_app, label == null ? pkg : label);
            try {
                Drawable icon = context.getPackageManager().getApplicationIcon(pkg);
                views.setImageViewBitmap(R.id.widget_icon, toBitmap(icon));
            } catch (Throwable t) {
                views.setImageViewBitmap(R.id.widget_icon, appIcon(context));
            }
        } else {
            views.setTextViewText(R.id.widget_app, "点按选择云游戏");
            views.setImageViewBitmap(R.id.widget_icon, appIcon(context));
        }

        PendingIntent launch = PendingIntent.getActivity(context, 1,
                new Intent(context, CloudLaunchActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, launch);

        if (!compact) {
            views.setViewVisibility(R.id.widget_hint, minHeight >= 96
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            PendingIntent pick = PendingIntent.getActivity(context, 2,
                    new Intent(context, AppPickerActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_change, pick);
        }

        manager.updateAppWidget(widgetId, views);
    }

    private static Bitmap appIcon(Context context) {
        try {
            return toBitmap(context.getPackageManager().getApplicationIcon(context.getPackageName()));
        } catch (Throwable t) {
            return Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        }
    }

    private static Bitmap toBitmap(Drawable drawable) {
        int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return bitmap;
    }
}
