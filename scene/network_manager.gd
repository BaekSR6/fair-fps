extends Node

const HOST := "127.0.0.1"
const PORT := 7777
const SEND_INTERVAL := 0.05

@onready var player1 = $"../Player"
@onready var player2 = $"../Player2"

var tcp := StreamPeerTCP.new()
var my_id: int = 0
var receive_buffer: String = ""
var send_timer: float = 0.0

func _ready() -> void:
	#서버가 플레이어 번호를 배정할 때까지 입력 차단
	player1.is_local_player = false
	player2.is_local_player = false
	
	var error := tcp.connect_to_host(HOST, PORT)
	
	if error != OK:
		push_error("서버 연결 시작실패: " + str(error))
	else:
		print("서버 연결 시도 중...")
		
func _process(delta: float) -> void:
	tcp.poll()
	
	var status := tcp.get_status()
	
	if status == StreamPeerTCP.STATUS_ERROR:
		push_error("서버 연결 오류")
		set_process(false)
		return
		
	if status != StreamPeerTCP.STATUS_CONNECTED:
		return
		
	receive_messages()
	
	if my_id == 0:
		return
		
	send_timer += delta
	
	if send_timer >= SEND_INTERVAL:
		send_timer = 0.0
		send_my_position()
		
func receive_messages() -> void:
	var available := tcp.get_available_bytes()
	
	if available > 0:
		receive_buffer += tcp.get_utf8_string(available)
		
	while receive_buffer.contains("\n"):
		var newline_index := receive_buffer.find("\n")
		var line := receive_buffer.substr(0, newline_index).strip_edges()
		
		receive_buffer = receive_buffer.substr(newline_index + 1)
		
		var message = JSON.parse_string(line)
		
		if typeof(message) == TYPE_DICTIONARY:
			handle_message(message)
			
func handle_message(message: Dictionary) -> void:
	match message.get("type", ""):
		"welcome":
			my_id = int(message.get("id", 0))
			
			player1.is_local_player = (my_id == 1)
			player2.is_local_player = (my_id == 2)
			
			player1.get_node("Camera2D").enabled = (my_id == 1)
			player2.get_node("Camera2D").enabled = (my_id == 2)
			
			print("서버 접속 완료! 내 번호: ", my_id)
			
			get_local_player().update_hud()
			
		"position":
			var other_id := int(message.get("id", 0))
			var target = get_player(other_id)
			
			if target != null and other_id != my_id:
				target.global_position = Vector2(
					float(message.get("x", 0)),
					float(message.get("y", 0))
				)
				
				target.global_rotation = float(message.get("rotation", 0))
				print("받을 값 회전도 : ", rad_to_deg(target.global_rotation))
		"leave":
			print("상대 플레이어 연결 종료: ", message.get("id"))
			
func send_my_position() -> void:
	var player = get_local_player()
	
	if player == null:
		return
		
	var message := JSON.stringify({
		"type": "position",
		"x": player.global_position.x,
		"y": player.global_position.y,
		"rotation": player.global_rotation
	}) + "\n"
	
	tcp.put_data(message.to_utf8_buffer())
	
func get_player(id: int):
	match id:
		1:
			return player1
		2:
			return player2
			
	return null
	
func get_local_player():
	return get_player(my_id)
