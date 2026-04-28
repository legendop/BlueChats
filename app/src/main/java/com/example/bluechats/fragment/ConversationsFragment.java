package com.example.bluechats.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.bluechats.R;
import com.example.bluechats.adapter.ConversationAdapter;
import com.example.bluechats.controller.ChatActivity;
import com.example.bluechats.database.AppDatabase;
import com.example.bluechats.database.MyChatDao;
import com.example.bluechats.model.Conversation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConversationsFragment extends Fragment {
    private RecyclerView conversationsRecyclerView;
    private ConversationAdapter adapter;
    private MyChatDao chatDao;
    private TextView txtStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_conversations, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        conversationsRecyclerView = view.findViewById(R.id.conversationsRecyclerView);
        txtStatus = view.findViewById(R.id.txtStatus);

        chatDao = AppDatabase.getInstance(requireContext()).myChatDao();

        conversationsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ConversationAdapter(new ArrayList<>(), this::openChat);
        conversationsRecyclerView.setAdapter(adapter);

        loadConversations();
    }

    private void loadConversations() {
        new Thread(() -> {
            Map<String, Conversation> conversationMap = new HashMap<>();

            chatDao.getAllChats().forEach(chat -> {
                if (chat.isSentByMe && chat.senderId.equals(chat.destId)) {
                    return;
                }

                String contactId = chat.isSentByMe ? chat.destId : chat.senderId;

                if (contactId == null || contactId.isEmpty()) {
                    return;
                }

                if (!conversationMap.containsKey(contactId)) {
                    Conversation conv = new Conversation();
                    conv.contactId = contactId;
                    conv.contactName = contactId.substring(0, Math.min(8, contactId.length()));
                    conv.lastMessage = chat.text.isEmpty() ? "No messages yet - Say hi!" : chat.text;
                    conv.timestamp = chat.timestamp;
                    conversationMap.put(contactId, conv);
                } else {
                    Conversation conv = conversationMap.get(contactId);
                    if (chat.timestamp > conv.timestamp && !chat.text.isEmpty()) {
                        conv.lastMessage = chat.text;
                        conv.timestamp = chat.timestamp;
                    }
                }
            });

            List<Conversation> conversations = new ArrayList<>(conversationMap.values());
            conversations.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    adapter.updateConversations(conversations);
                    txtStatus.setText("Status: " + conversations.size() + " conversation(s)");
                });
            }
        }).start();
    }

    private void openChat(String contactId) {
        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra("contactId", contactId);
        startActivity(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadConversations();
    }
}
