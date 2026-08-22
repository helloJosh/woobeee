"use client"

import { useRef, useState } from "react"
import { Loader2, Trash2 } from "lucide-react"

import ProfileAvatar from "@/components/auth/profile-avatar"
import { Button } from "@/components/ui/button"
import { authAPI } from "@/lib/api"
import {
    PROFILE_IMAGE_ACCEPT,
    describeProfileImageError,
    pickImageFile,
    validateProfileImageFile,
} from "@/lib/profile-image"
import { useAuth } from "@/hooks/use-auth"
import type { MemberProfile } from "@/lib/types"

/**
 * 마이페이지의 프로필 카드. 아바타가 곧 업로드 표면이다 — 호버하면 "변경" 이 덮이고, 클릭하면
 * 파일 선택창이 뜨고, 드롭도 받는다(블로그 편집기와 같은 감각).
 *
 * 검증은 이 컴포넌트가 하지 않는다. `lib/profile-image` 가 판단하고 여기서는 결과를 그린다 —
 * 컴포넌트에는 스펙이 없으므로 판단이 여기로 넘어오면 검증 밖으로 나간다.
 */
export default function ProfileImageEditor({
    profile,
    onProfileChange,
}: {
    profile: MemberProfile
    onProfileChange: (profile: MemberProfile) => void
}) {
    const { profileImageUrl, refreshProfileImage } = useAuth()
    const inputRef = useRef<HTMLInputElement>(null)

    const [busy, setBusy] = useState(false)
    const [dragging, setDragging] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const submit = async (file: File) => {
        const validation = validateProfileImageFile(file)
        if (!validation.ok) {
            setError(validation.reason)
            return
        }

        setBusy(true)
        setError(null)
        try {
            onProfileChange(await authAPI.uploadProfileImage(file))
            await refreshProfileImage()
        } catch (cause) {
            setError(describeProfileImageError(cause))
        } finally {
            setBusy(false)
        }
    }

    const remove = async () => {
        setBusy(true)
        setError(null)
        try {
            await authAPI.deleteProfileImage()
            onProfileChange({ ...profile, hasProfileImage: false })
            await refreshProfileImage()
        } catch (cause) {
            setError(describeProfileImageError(cause))
        } finally {
            setBusy(false)
        }
    }

    return (
        <div className="space-y-3 rounded-lg border p-5">
            <div className="flex items-center gap-4">
                <button
                    type="button"
                    disabled={busy}
                    onClick={() => inputRef.current?.click()}
                    onDragOver={(event) => {
                        event.preventDefault()
                        setDragging(true)
                    }}
                    onDragLeave={() => setDragging(false)}
                    onDrop={(event) => {
                        event.preventDefault()
                        setDragging(false)
                        const dropped = pickImageFile(Array.from(event.dataTransfer.files))
                        if (dropped) {
                            void submit(dropped)
                        }
                    }}
                    aria-label="프로필 이미지 변경"
                    className="group relative h-16 w-16 shrink-0 rounded-full ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-progress"
                >
                    <ProfileAvatar
                        src={profileImageUrl}
                        sizeClassName="h-16 w-16"
                        iconClassName="h-7 w-7"
                        className={dragging ? "ring-2 ring-primary" : undefined}
                    />

                    {/* 호버·포커스·드래그 중에만 덮는다. 평소에는 사진을 가리지 않는다. */}
                    <span
                        className={`absolute inset-0 flex items-center justify-center rounded-full bg-black/55 text-xs font-medium text-white transition-opacity ${
                            busy || dragging ? "opacity-100" : "opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100"
                        }`}
                    >
                        {busy ? <Loader2 aria-hidden className="h-5 w-5 animate-spin" /> : "변경"}
                    </span>
                </button>

                <div className="min-w-0 flex-1">
                    <p className="truncate text-lg font-medium">{profile.nickname}</p>
                    <p className="truncate text-sm text-muted-foreground">{profile.email}</p>
                    <p className="mt-1 text-sm">게임 머니 {profile.gameMoney.toLocaleString()}</p>
                </div>

                {profile.hasProfileImage ? (
                    <Button variant="ghost" size="sm" disabled={busy} onClick={remove} className="shrink-0">
                        <Trash2 className="mr-1.5 h-4 w-4" />
                        삭제
                    </Button>
                ) : null}
            </div>

            <input
                ref={inputRef}
                type="file"
                accept={PROFILE_IMAGE_ACCEPT}
                className="hidden"
                onChange={(event) => {
                    const selected = event.target.files?.[0]
                    // 같은 파일을 다시 고를 수 있게 값을 비운다 — 안 비우면 두 번째 선택에
                    // change 가 오지 않는다.
                    event.target.value = ""
                    if (selected) {
                        void submit(selected)
                    }
                }}
            />

            <p className="text-xs text-muted-foreground">
                png · jpeg · webp · gif, 5MB 이하. 아바타를 누르거나 이미지를 끌어다 놓으세요.
            </p>

            {error ? <p className="text-sm text-destructive">{error}</p> : null}
        </div>
    )
}
