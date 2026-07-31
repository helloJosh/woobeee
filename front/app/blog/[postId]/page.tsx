"use client"

import PostDetail from "@/components/post-detail"

interface PostPageProps {
  params: {
    postId: string
  }
}

export default function PostPage({ params }: PostPageProps) {
  const { postId } = params

  return <PostDetail postId={Number(postId)} />
}
