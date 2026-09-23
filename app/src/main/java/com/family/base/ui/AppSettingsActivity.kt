<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <!-- ===== ВЕРХНЯЯ ПАНЕЛЬ ===== -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginBottom="16dp"
        android:gravity="center_vertical">

        <Button
            android:id="@+id/btnBack"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="← Назад"
            style="@style/Widget.MaterialComponents.Button.OutlinedButton" />

        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="⚙️ Настройки приложения"
            android:textAppearance="?attr/textAppearanceHeadline6"
            android:gravity="center" />
    </LinearLayout>

    <!-- ===== ЗАГЛУШКА ===== -->
    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Здесь будут настройки темы, языка и другие параметры (в будущем)."
        android:textSize="14sp"
        android:textColor="@android:color/darker_gray"
        android:layout_marginTop="16dp" />

</LinearLayout>
