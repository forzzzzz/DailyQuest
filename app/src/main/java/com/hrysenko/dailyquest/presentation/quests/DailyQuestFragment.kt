package com.hrysenko.dailyquest.presentation.quests

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hrysenko.dailyquest.R
import com.hrysenko.dailyquest.models.AppDatabase
import com.hrysenko.dailyquest.services.PedometerService
import com.hrysenko.dailyquest.presentation.quests.QuestGenerator
import com.hrysenko.dailyquest.presentation.quests.Quest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Calendar

class DailyQuestFragment : Fragment() {

    private lateinit var database: AppDatabase
    private lateinit var questsRecyclerView: RecyclerView
    private lateinit var streakCounter: TextView
    private lateinit var questAdapter: QuestAdapter
    private var quests: MutableList<Quest> = mutableListOf()
    private var lastQuestDate: String? = null
    private var currentSteps: Int = 0

    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val steps = intent?.getIntExtra(PedometerService.EXTRA_STEPS, 0) ?: 0
            Log.d("DailyQuestFragment", "Received steps: $steps")
            currentSteps = steps
            updateStepQuestProgress(steps)
            questAdapter.updateSteps(steps)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_daily_quest, container, false)
        database = AppDatabase.getDatabase(requireContext())
        questsRecyclerView = view.findViewById(R.id.quests_recycler_view)
        streakCounter = view.findViewById(R.id.streak_counter)

        setupRecyclerView()
        loadUserAndQuests()
        scheduleDailyNotification()


        ContextCompat.registerReceiver(
            requireContext(),
            stepReceiver,
            IntentFilter(PedometerService.STEP_UPDATE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )


        val serviceIntent = Intent(requireContext(), PedometerService::class.java)
        requireContext().startService(serviceIntent)

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireContext().unregisterReceiver(stepReceiver)
    }

    private fun setupRecyclerView() {
        questAdapter = QuestAdapter(currentSteps) { quest ->
            if (quest.name != getString(R.string.quest_walk)) {
                toggleQuestCompletion(quest)
            }
        }
        questsRecyclerView.layoutManager = LinearLayoutManager(context)
        questsRecyclerView.adapter = questAdapter
    }

    private fun loadUserAndQuests() {
        CoroutineScope(Dispatchers.IO).launch {
            val user = database.userDao().getUser()
            val today = LocalDate.now().toString()

            Log.d("DailyQuestFragment", "User: $user, Today: $today, LastQuestDate: $lastQuestDate, QuestCount: ${quests.size}")


            if (lastQuestDate != today || quests.isEmpty()) {
                quests.clear()
                val newQuests = QuestGenerator.generateDailyQuests(requireContext(), user)
                quests.addAll(newQuests)
                lastQuestDate = today

                quests.forEach { quest ->
                    quest.completed = isQuestCompleted(quest)
                }
                Log.d("DailyQuestFragment", "Generated ${newQuests.size} quests: $newQuests")
            }

            val streak = getStreak()
            currentSteps = PedometerService.getCurrentSteps()
            withContext(Dispatchers.Main) {
                questAdapter.submitList(quests.toList(), currentSteps)
                streakCounter.text = "Day streak: $streak"
                updateStepQuestProgress(currentSteps)
                Log.d("DailyQuestFragment", "Updated UI with ${quests.size} quests")
            }
        }
    }

    private fun updateStepQuestProgress(steps: Int) {
        quests.forEach { quest ->
            if (quest.name == getString(R.string.quest_walk)) {
                quest.completed = steps >= quest.amount
                saveQuestCompletion(quest)
                Log.d("DailyQuestFragment", "Walk quest status: $steps/${quest.amount}, Completed: ${quest.completed}")
                if (quest.completed && quests.all { it.completed }) {
                    updateStreak()
                }
            }
        }
        questAdapter.notifyDataSetChanged()
    }

    private fun toggleQuestCompletion(quest: Quest) {
        quest.completed = !quest.completed
        saveQuestCompletion(quest)
        Log.d("DailyQuestFragment", "Quest ${quest.name} completion toggled to ${quest.completed}")
        if (quests.all { it.completed }) {
            updateStreak()
        }
        questAdapter.notifyDataSetChanged()
    }

    private fun saveQuestCompletion(quest: Quest) {
        val preferences = requireContext().getSharedPreferences("DailyQuestPrefs", Context.MODE_PRIVATE)
        with(preferences.edit()) {
            putBoolean("quest_${quest.name}_${quest.date}", quest.completed)
            apply()
        }
    }

    private fun isQuestCompleted(quest: Quest): Boolean {
        val preferences = requireContext().getSharedPreferences("DailyQuestPrefs", Context.MODE_PRIVATE)
        return preferences.getBoolean("quest_${quest.name}_${quest.date}", false)
    }

    private fun updateStreak() {
        val preferences = requireContext().getSharedPreferences("DailyQuestPrefs", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        val lastStreakDate = preferences.getString("lastStreakDate", null)
        val currentStreak = preferences.getInt("streak", 0)
        val today = LocalDate.now().toString()

        if (lastStreakDate == null || lastStreakDate != today) {
            editor.putInt("streak", currentStreak + 1)
            editor.putString("lastStreakDate", today)
            editor.apply()
            Log.d("DailyQuestFragment", "Streak updated to ${currentStreak + 1}")
        }

        streakCounter.text = "Day streak: ${getStreak()}"
    }

    private fun getStreak(): Int {
        val preferences = requireContext().getSharedPreferences("DailyQuestPrefs", Context.MODE_PRIVATE)
        return preferences.getInt("streak", 0)
    }

    private fun scheduleDailyNotification() {
        val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(requireContext(), QuestNotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            requireContext(),
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }
}

class QuestAdapter(private var currentSteps: Int, private val onQuestToggle: (Quest) -> Unit) :
    RecyclerView.Adapter<QuestAdapter.QuestViewHolder>() {

    private var quests: List<Quest> = emptyList()

    fun submitList(newQuests: List<Quest>, steps: Int) {
        quests = newQuests
        currentSteps = steps
        notifyDataSetChanged()
    }

    fun updateSteps(steps: Int) {
        currentSteps = steps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.daily_item_quests, parent, false)
        return QuestViewHolder(view)
    }

    override fun onBindViewHolder(holder: QuestViewHolder, position: Int) {
        holder.bind(quests[position], currentSteps)
    }

    override fun getItemCount(): Int = quests.size

    inner class QuestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val questName: TextView = itemView.findViewById(R.id.quest_name)
        private val questAmount: TextView = itemView.findViewById(R.id.quest_amount)
        private val complexity: TextView = itemView.findViewById(R.id.complexity)
        private val doneButton: MaterialButton = itemView.findViewById(R.id.done_btn)

        fun bind(quest: Quest, currentSteps: Int) {
            questName.text = quest.name
            questAmount.text = if (quest.name == itemView.context.getString(R.string.quest_walk)) {
                "$currentSteps/${quest.amount}"
            } else {
                quest.amount.toString()
            }
            complexity.text = quest.complexity
            if (quest.name == itemView.context.getString(R.string.quest_walk)) {
                // Disable manual toggle for Walk quest
                doneButton.text = if (quest.completed) itemView.context.getString(R.string.completed) else itemView.context.getString(R.string.in_progress)
                doneButton.isEnabled = false
            } else {
                doneButton.text = if (quest.completed) itemView.context.getString(R.string.undo) else itemView.context.getString(R.string.done)
                doneButton.isEnabled = true
                doneButton.setOnClickListener {
                    onQuestToggle(quest)
                }
            }
        }
    }
}

class QuestNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = context.getSharedPreferences("DailyQuestPrefs", Context.MODE_PRIVATE)
        val lastStreakDate = preferences.getString("lastStreakDate", null)
        val today = LocalDate.now().toString()

        if (lastStreakDate != today) {
            showNotification(context)
        }
    }

    private fun showNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "quest_reminder"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.quest_reminders),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.daily_quests_reminder))
            .setContentText(context.getString(R.string.uncompleted_quests))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(1, notification)
    }
}