#!/usr/bin/env python3
"""
Basic Python Server for STOMP Assignment – Stage 3.3

IMPORTANT:
DO NOT CHANGE the server name or the basic protocol.+

Students should EXTEND this server by implementing
the methods below.
"""

import socket
import sys
import threading
import sqlite3
from typing import Tuple

SERVER_NAME = "STOMP_PYTHON_SQL_SERVER"  # DO NOT CHANGE!
DB_FILE = "stomp_server.db"              # DO NOT CHANGE!
db_lock = threading.Lock()               # For thread-safe DB access


def recv_null_terminated(sock: socket.socket) -> str:
    data = b""
    while True:
        chunk = sock.recv(1024)
        if not chunk:
            return ""
        data += chunk
        if b"\0" in data:
            msg, _ = data.split(b"\0", 1)
            return msg.decode("utf-8", errors="replace")


def init_database():
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()

    cursor.execute("""
    CREATE TABLE IF NOT EXISTS users (
        username TEXT PRIMARY KEY,
        password TEXT NOT NULL,
        registration_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP 
    )
    """)

    cursor.execute("""
    CREATE TABLE IF NOT EXISTS login_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        logout_time TIMESTAMP,
        username TEXT NOT NULL,
        FOREIGN KEY (username) REFERENCES users(username)
    )
    """)

    cursor.execute("""
    CREATE TABLE IF NOT EXISTS file_tracking (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        filename TEXT NOT NULL,
        game_channel TEXT,
        upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        username TEXT NOT NULL,
        FOREIGN KEY (username) REFERENCES users(username)
    )
    """)

    conn.commit()
    conn.close()


def execute_sql_command(sql_command: str) -> str:
    """Execute INSERT, UPDATE, DELETE commands"""
    try:
        print(f"[SQL_COMMAND] Executing: {sql_command[:100]}..." if len(sql_command) > 100 else f"[SQL_COMMAND] Executing: {sql_command}")
        conn = sqlite3.connect(DB_FILE)
        # enable foreign key constraints
        conn.execute("PRAGMA foreign_keys = ON")  
        cursor = conn.cursor()
        cursor.execute(sql_command)
        conn.commit()
        rows_affected = cursor.rowcount
        conn.close()
        result = f"SUCCESS: {rows_affected} rows affected"
        print(f"[SQL_COMMAND] Result: {result}")
        return result
    except Exception as e:
        error_msg = f"ERROR: {str(e)}"
        print(f"[SQL_COMMAND] {error_msg}")
        return error_msg



def execute_sql_query(sql_query: str) -> str:
    """Execute SELECT queries and return results"""
    try:
        print(f"[SQL_QUERY] Executing: {sql_query[:100]}..." if len(sql_query) > 100 else f"[SQL_QUERY] Executing: {sql_query}")
        conn = sqlite3.connect(DB_FILE)
        conn.execute("PRAGMA foreign_keys = ON")  # Enable foreign key constraints
        cursor = conn.cursor()
        cursor.execute(sql_query)
        results = cursor.fetchall()
        conn.close()
        
        # Format results as: SUCCESS|field1,field2,field3|field1,field2,field3...
        # Each row's fields are comma-separated, rows are pipe-separated
        if results:
            formatted_rows: list[str] = []
            for row in results:
                # Convert each field to string, replace None with empty string
                fields = [str(field) if field is not None else "" for field in row]
                # Join fields with comma
                formatted_rows.append(",".join(fields))
            result_str = "SUCCESS|" + "|".join(formatted_rows)
            print(f"[SQL_QUERY] Result: {len(results)} row(s) returned")
            return result_str
        else:
            print(f"[SQL_QUERY] Result: No rows returned")
            return "SUCCESS|"
    except Exception as e:
        error_msg = f"ERROR: {str(e)}"
        print(f"[SQL_QUERY] {error_msg}")
        return error_msg

# handle client connections
def handle_client(client_socket: socket.socket, addr: Tuple[str, int]):
    print(f"[{SERVER_NAME}] Client connected from {addr}")

    try:
        while True:
            message = recv_null_terminated(client_socket)
            if message == "":
                break

            print(f"[{SERVER_NAME}] Received from {addr}:")
            print(f"  {message[:100]}..." if len(message) > 100 else f"  {message}")

            # cheak  to determine if it's a query or command
            sql_upper = message.strip().upper()
            if sql_upper.startswith('SELECT'):
                response = execute_sql_query(message)
            else:
                response = execute_sql_command(message)
            
            print(f"[{SERVER_NAME}] Sending response to {addr}: {response[:100]}..." if len(response) > 100 else f"[{SERVER_NAME}] Sending response to {addr}: {response}")
            client_socket.sendall((response + "\0").encode('utf-8'))

    except Exception as e:
        print(f"[{SERVER_NAME}] Error handling client {addr}: {e}")
    finally:
        try:
            client_socket.close()
        except Exception:
            pass
        print(f"[{SERVER_NAME}] Client {addr} disconnected")



    
def start_server(host="127.0.0.1", port=7778):
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_socket.bind((host, port))
        server_socket.listen(5)
        print(f"[{SERVER_NAME}] Server started on {host}:{port}")
        print(f"[{SERVER_NAME}] Waiting for connections...")

        while True:
            client_socket, addr = server_socket.accept()
            t = threading.Thread(
                target=handle_client,
                args=(client_socket, addr),
                daemon=True
            )
            t.start()

    except KeyboardInterrupt:
        print(f"\n[{SERVER_NAME}] Shutting down server...")
    finally:
        try:
            server_socket.close()
        except Exception:
            pass


if __name__ == "__main__":
    print(f"[{SERVER_NAME}] Initializing database...")
    init_database()
    print(f"[{SERVER_NAME}] Database initialized")
    port = 7778
    if len(sys.argv) > 1:
        raw_port = sys.argv[1].strip()
        try:
            port = int(raw_port)
        except ValueError:
            print(f"Invalid port '{raw_port}', falling back to default {port}")

    start_server(port=port)
