"use client"

import { LogOut, Settings, UserCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar"
import { useAuth } from "@/hooks/use-auth"

export default function UserMenu() {
    const { user, logout } = useAuth()

    if (!user) return null

    const handleSignOut = async () => {
        try {
            await logout()
        } catch (error) {
            console.error("Sign out error:", error)
        }
    }

    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="ghost" className="relative h-8 w-8 rounded-full">
                    <Avatar className="h-8 w-8">
                        <AvatarImage src={user.profileImage || "/placeholder.svg"} alt={user.name || user.email} />
                        <AvatarFallback>{user.name?.charAt(0) || user.email?.charAt(0) || "U"}</AvatarFallback>
                    </Avatar>
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent className="w-56" align="end" forceMount>
                <DropdownMenuLabel className="font-normal">
                    <div className="flex flex-col space-y-1">
                        <p className="text-sm font-medium leading-none">{user.name || "사용자"}</p>
                        <p className="text-xs leading-none text-muted-foreground">{user.email}</p>
                    </div>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem>
                    <UserCircle className="mr-2 h-4 w-4" />
                    <span>프로필</span>
                </DropdownMenuItem>
                <DropdownMenuItem>
                    <Settings className="mr-2 h-4 w-4" />
                    <span>설정</span>
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={handleSignOut}>
                    <LogOut className="mr-2 h-4 w-4" />
                    <span>로그아웃</span>
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    )
}
