import DS from 'ember-data';

export default DS.Model.extend({
	  comment: DS.attr('string'),
	  groupId : DS.attr('number'),
	  groupName: DS.attr('string'),
	  title: DS.attr('string'),
	  createdByEmail: DS.attr('string'),
	  createdTime: DS.attr('number'),
	  updatedTime: DS.attr('number'),
	  deadlineTime: DS.attr('number'),
	  updatedByName: DS.attr('string'),
	  createdByName: DS.attr('string'),
	  updatedByEmail: DS.attr('string'),
	  isSaving : DS.attr('boolean'),
	  isSubmitted : DS.attr('boolean'),
	  canSubmit : DS.attr('boolean'),
	  isTask :  DS.attr( {
		    defaultValue() { true }
	  }),
	  groupIds :DS.attr( {
		    defaultValue() { return []; }
	  }),
	  files: DS.attr( {
		    defaultValue() { return []; }
		  }),
	recipients: DS.attr( {
	    defaultValue() { return []; }
	  }),
	comments: DS.attr( {
	    defaultValue() { return []; }
	  }),
	numberOfComments: DS.attr('number'),
});
