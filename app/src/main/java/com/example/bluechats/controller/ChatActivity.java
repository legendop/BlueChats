package com.example.bluechats.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.bluechats.R;
import com.example.bluechats.adapter.ChatMessageAdapter;
import com.example.bluechats.database.AppDatabase;
import com.example.bluechats.database.MyChatDao;
import com.example.bluechats.database.MyChatEntity;
import com.example.bluechats.model.BleMeshManager;
import com.example.bluechats.model.ChatMessage;
import com.example.bluechats.model.ChatMessageItem;

import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends AppCompatActivity {
    private static final String TAG = "ChatActivity";
    private RecyclerView chatRecyclerView;
    private ChatMessageAdapter adapter;
    private EditText messageInput;
    private Button sendButton;
    private TextView txtChatHeader;
    private MyChatDao chatDao;
    private String contactId;
    private String localId;
    private BleMeshManager meshManager;
    private BroadcastReceiver messageReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        contactId = getIntent().getStringExtra("contactId");
        localId = getSharedPreferences("bluechats", MODE_PRIVATE).getString("localId", "unknown");

        txtChatHeader = findViewById(R.id.txtChatHeader);
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);

        txtChatHeader.setText("Chat with: " + contactId.substring(0, Math.min(12, contactId.length())));

        chatDao = AppDatabase.getInstance(this).myChatDao();
        meshManager = BleMeshManager.getInstance(this);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        chatRecyclerView.setLayoutManager(layoutManager);
        adapter = new ChatMessageAdapter(new ArrayList<>());
        chatRecyclerView.setAdapter(adapter);

        sendButton.setOnClickListener(v -> sendMessage());

        setupMessageReceiver();
        loadMessages();
    }

    private void loadMessages() {
        new Thread(() -> {
            List<MyChatEntity> chats = chatDao.getChatsWith(contactId, localId);
            List<ChatMessageItem> messages = new ArrayList<>();

            for (MyChatEntity chat : chats) {
                ChatMessageItem item = new ChatMessageItem();
                item.text = chat.text;
                item.timestamp = chat.timestamp;
                item.isSentByMe = chat.isSentByMe;
                messages.add(item);
            }

            runOnUiThread(() -> {
                adapter.updateMessages(messages);
                chatRecyclerView.scrollToPosition(messages.size() - 1);
            });
        }).start();
    }

    private void sendMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }

        messageInput.setText("");

        ChatMessage message = ChatMessage.createDataMessage(localId, contactId, text, 10);

        new Thread(() -> {
            MyChatEntity chatEntity = new MyChatEntity(
                    message.msgId,
                    localId,
                    contactId,
                    text,
                    System.currentTimeMillis(),
                    true,
                    false
            );
            chatDao.insert(chatEntity);

            runOnUiThread(() -> {
                loadMessages();
                Log.i(TAG, "Sending message to " + contactId + ": " + text);
            });
        }).start();

        meshManager.sendMessage(message);
    }

    private void setupMessageReceiver() {
        messageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String fromId = intent.getStringExtra("senderId");
                if (fromId != null && fromId.equals(contactId)) {
                    loadMessages();
                }
            }
        };
        registerReceiver(messageReceiver, new IntentFilter("com.example.bluechats.MESSAGE_RECEIVED"), RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMessages();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (messageReceiver != null) {
            unregisterReceiver(messageReceiver);
        }
    }
}
