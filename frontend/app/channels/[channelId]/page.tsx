import { MessageFeed } from "@/components/MessageFeed";

export default async function ChannelPage({
  params,
}: {
  params: Promise<{ channelId: string }>;
}) {
  const { channelId } = await params;
  return <MessageFeed channelId={channelId} />;
}
