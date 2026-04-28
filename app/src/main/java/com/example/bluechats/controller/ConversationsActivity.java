package com.example.bluechats.controller;

import android.content.Intent;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.bluechats.R;
import com.example.bluechats.adapter.ConversationAdapter;
import com.example.bluechats.database.AppDatabase;
import com.example.bluechats.database.MyChatDao;
import com.example.bluechats.model.Conversation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConversationsActivity extends AppCompatActivity {
    private RecyclerView conversationsRecyclerView;
    private ConversationAdapter adapter;
    private MyChatDao chatDao;
    private TextView txtStatus;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversations);

        conversationsRecyclerView = findViewById(R.id.conversationsRecyclerView);
        txtStatus = findViewById(R.id.txtStatus);

        chatDao = AppDatabase.getInstance(this).myChatDao();

        conversationsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConversationAdapter(new ArrayList<>(), this::openChat);
        conversationsRecyclerView.setAdapter(adapter);

        gestureDetector = new GestureDetector(this, new SwipeGestureListener());

        loadConversations();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private void loadConversations() {
        new Thread(() -> {
            Map<String, Conversation> conversationMap = new HashMap<>();

            chatDao.getAllChats().forEach(chat -> {
                String contactId = chat.isSentByMe ? chat.destId : chat.senderId;

                if (!conversationMap.containsKey(contactId)) {
                    Conversation conv = new Conversation();
                    conv.contactId = contactId;
                    conv.contactName = contactId.substring(0, Math.min(8, contactId.length()));
                    conv.lastMessage = chat.text;
                    conv.timestamp = chat.timestamp;
                    conversationMap.put(contactId, conv);
                } else {
                    Conversation conv = conversationMap.get(contactId);
                    if (chat.timestamp > conv.timestamp) {
                        conv.lastMessage = chat.text;
                        conv.timestamp = chat.timestamp;
                    }
                }
            });

            List<Conversation> conversations = new ArrayList<>(conversationMap.values());
            conversations.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

            runOnUiThread(() -> {
                adapter.updateConversations(conversations);
                txtStatus.setText("Status: " + conversations.size() + " conversation(s)");
            });
        }).start();
    }

    private void openChat(String contactId) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("contactId", contactId);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadConversations();
    }

    private class SwipeGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffX > 0) {
                    finish();
                    overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
                    return true;
                }
            }
            return false;
        }
    }
}
