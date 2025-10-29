"use client";
import { useEffect, useState } from "react";

interface User {
    id: number;
    name: string;
}

export default function UsersPage() {
    const [users, setUsers] = useState<User[]>([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        // 환경 변수로 API URL 지정
        fetch(`http://localhost/api/users`)
            .then(res => res.json())
            .then(data => {
                setUsers(data);
                setLoading(false);
            })
            .catch(err => {
                console.error(err);
                setLoading(false);
            });
    }, []);

    if (loading) return <p>Loading...</p>;

    return (
        <div style={{ padding: "2rem" }}>
            <h1>User List</h1>
            <ul>
                {users.map(user => (
                    <li key={user.id}>
                        {user.id} - {user.name}
                    </li>
                ))}
            </ul>
        </div>
    );
}