package com.family.base.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.family.base.databinding.ActivityStatsAndAccountingBinding
import com.family.base.util.Logger

class StatsAndAccountingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsAndAccountingBinding
    private val TAG = "StatsAndAccountingActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.log(TAG, "=== StatsAndAccountingActivity onCreate START ===")

        try {
            binding = ActivityStatsAndAccountingBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Logger.log(TAG, "Binding inflated successfully")
        } catch (e: Exception) {
            Logger.log(TAG, "CRITICAL: Failed to inflate layout", e)
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        // ===== СТАТИСТИКА РАСХОДОВ =====
        binding.btnStatistics.setOnClickListener {
            Logger.log(TAG, "Statistics clicked")
            startActivity(Intent(this, StatisticsActivity::class.java))
        }

        // ===== АРХИВ ПРЕДМЕТОВ =====
        binding.btnArchive.setOnClickListener {
            Logger.log(TAG, "Archive clicked")
            startActivity(Intent(this, ArchiveActivity::class.java))
        }

        // ===== ВЫДАННЫЕ ПРЕДМЕТЫ =====
        binding.btnLentItems.setOnClickListener {
            Logger.log(TAG, "Lent items clicked")
            startActivity(Intent(this, LentItemsActivity::class.java))
        }

        Logger.log(TAG, "=== StatsAndAccountingActivity onCreate FINISHED ===")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(TAG, "onDestroy called")
    }
}
