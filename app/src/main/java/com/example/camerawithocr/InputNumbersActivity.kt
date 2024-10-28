package com.example.camerawithocr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class InputNumbersActivity : AppCompatActivity() {

    // SharedPreferences key for storing the groups
    private val PREFS_NAME = "UserGroupsPrefs"
    private val GROUP_1 = "GROUP_1"
    private val GROUP_2 = "GROUP_2"
    private val GROUP_3 = "GROUP_3"
    private val GROUP_4 = "GROUP_4"
    private val GROUP_5 = "GROUP_5"
    private val GROUP_6 = "GROUP_6"
    private val GROUP_7 = "GROUP_7"
    private val GROUP_8 = "GROUP_8"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_numbers)

        // Get references to the input fields
        val group1 = findViewById<EditText>(R.id.group1)
        val group2 = findViewById<EditText>(R.id.group2)
        val group3 = findViewById<EditText>(R.id.group3)
        val group4 = findViewById<EditText>(R.id.group4)
        val group5 = findViewById<EditText>(R.id.group5)
        val group6 = findViewById<EditText>(R.id.group6)
        val group7 = findViewById<EditText>(R.id.group7)
        val group8 = findViewById<EditText>(R.id.group8)

        // Load the saved groups from SharedPreferences
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        group1.setText(sharedPreferences.getString(GROUP_1, "3524"))  // Pre-fill with saved value
        group2.setText(sharedPreferences.getString(GROUP_2, "1060"))
        group3.setText(sharedPreferences.getString(GROUP_3, "8732"))
        group4.setText(sharedPreferences.getString(GROUP_4, "8800"))
        group5.setText(sharedPreferences.getString(GROUP_5, "0211"))
        group6.setText(sharedPreferences.getString(GROUP_6, "5900"))
        group7.setText(sharedPreferences.getString(GROUP_7, "0689"))
        group8.setText(sharedPreferences.getString(GROUP_8, "8622"))

        val submitButton = findViewById<Button>(R.id.submit_button)

        submitButton.setOnClickListener {
            val g1 = group1.text.toString()
            val g2 = group2.text.toString()
            val g3 = group3.text.toString()
            val g4 = group4.text.toString()
            val g5 = group5.text.toString()
            val g6 = group6.text.toString()
            val g7 = group7.text.toString()
            val g8 = group8.text.toString()

            // Validate user input - ensure each group has 4 digits
            if (g1.length == 4 && g2.length == 4 && g3.length == 4 && g4.length == 4 && g5.length == 4 && g6.length == 4 && g7.length == 4 && g8.length == 4) {
                // Save the user input in SharedPreferences for future use
                with(sharedPreferences.edit()) {
                    putString(GROUP_1, g1)
                    putString(GROUP_2, g2)
                    putString(GROUP_3, g3)
                    putString(GROUP_4, g4)
                    putString(GROUP_5, g5)
                    putString(GROUP_6, g6)
                    putString(GROUP_7, g7)
                    putString(GROUP_8, g8)
                    apply()  // Save changes
                }

                // Pass the data back to MainActivity
                val resultIntent = Intent().apply {
                    putExtra("GROUP_1", g1)
                    putExtra("GROUP_2", g2)
                    putExtra("GROUP_3", g3)
                    putExtra("GROUP_4", g4)
                    putExtra("GROUP_5", g5)
                    putExtra("GROUP_6", g6)
                    putExtra("GROUP_7", g7)
                    putExtra("GROUP_8", g8)
                }
                setResult(RESULT_OK, resultIntent)
                finish()  // Close InputNumbersActivity and return to MainActivity
            } else {
                // Show a Toast message if validation fails
                Toast.makeText(this, "Please enter 4 digits for each group", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
