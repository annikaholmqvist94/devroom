export type Mention = {
  userId: string;
  isSystem: boolean;
  personality: string | null;
};

export type Message = {
  id: string;
  channelId: string;
  senderId: string;
  body: string;
  parentMessageId: string | null;
  mentions: Mention[];
  createdAt: string;
};

export type Me = {
  userId: string;
  displayName: string;
  teamId: string;
};

export type Channel = {
  id: string;
  name: string;
};

export type PostMessagePayload = {
  channelId: string;
  body: string;
  parentMessageId?: string | null;
};
