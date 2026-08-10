package skillapi

import (
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// SkillHandler groups the skill-marketplace handlers. It holds only the
// dependencies those handlers use.
type SkillHandler struct {
	ObjectStore  storage.Store
	SkillStorage storage.Store
	Store        store.Store
}

// NewSkillHandler constructs a SkillHandler.
func NewSkillHandler(objStore storage.Store, skillStore storage.Store, store store.Store) *SkillHandler {
	return &SkillHandler{
		ObjectStore:  objStore,
		SkillStorage: skillStore,
		Store:        store,
	}
}
