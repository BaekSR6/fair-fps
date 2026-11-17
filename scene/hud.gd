extends Control

@onready var health_bar: ProgressBar = $HealthBar
@onready var ammo_label: Label = $AmmoLabel

func _ready() -> void:
	print("HUD HealthBar: ", health_bar)
	
func update_health(current_hp: int, max_hp: int) -> void:
	health_bar.max_value = max_hp
	health_bar.value = current_hp
	
func update_ammo(current_ammo: int, magazine_size: int) -> void:
	ammo_label.text = "%d / %d" % [current_ammo, magazine_size]
