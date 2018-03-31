import DS from 'ember-data';

export default DS.Model.extend({
	dbFields : ["id","description", "subject" , "marks", "fromDateTime", "toDateTime", "passingRules","totalAppeared","groups","questions"],
	
	description: DS.attr('string'),
  subject: DS.attr('string'),
  marks: DS.attr('string'),
  passingRules: DS.attr('string'),
  fromDateTime: DS.attr('number'),
  toDateTime: DS.attr('number'),
  totalAppeared: DS.attr('number'),
	questions: DS.attr( ),
  groups: DS.attr( )


});
